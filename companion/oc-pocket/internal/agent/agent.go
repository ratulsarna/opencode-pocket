package agent

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/ratulsarna/opencode-pocket/companion/oc-pocket/internal/config"
	"github.com/ratulsarna/opencode-pocket/companion/oc-pocket/internal/gateway"
	"github.com/ratulsarna/opencode-pocket/companion/oc-pocket/internal/netutil"
	"github.com/ratulsarna/opencode-pocket/companion/oc-pocket/internal/tailscale"
)

type Options struct {
	ConfigDir string
	Config    config.Config
	Token     string
	Tailscale tailscale.Client
}

type Status struct {
	UpdatedAtMs int64  `json:"updatedAtMs"`
	LastError   string `json:"lastError"`
}

var cgnatIPv4s = netutil.CGNATIPv4s

func statusPath(configDir string) string {
	return filepath.Join(configDir, "status.json")
}

func ReadStatus(configDir string) (Status, bool) {
	raw, err := os.ReadFile(statusPath(configDir))
	if err != nil {
		return Status{}, false
	}
	var s Status
	if err := json.Unmarshal(raw, &s); err != nil {
		return Status{}, false
	}
	return s, true
}

func writeStatus(configDir string, lastErr string) {
	_ = os.MkdirAll(configDir, 0o700)
	s := Status{
		UpdatedAtMs: time.Now().UnixMilli(),
		LastError:   lastErr,
	}
	raw, _ := json.MarshalIndent(s, "", "  ")
	_ = os.WriteFile(statusPath(configDir), raw, 0o600)
}

func Run(ctx context.Context, opts Options) error {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()

	if opts.ConfigDir == "" {
		return errors.New("ConfigDir is required")
	}
	if strings.TrimSpace(opts.Token) == "" {
		return errors.New("Token is required")
	}
	if opts.Config.GatewayPort == 0 || opts.Config.OpenCodePort == 0 {
		return errors.New("invalid config ports")
	}
	if strings.TrimSpace(opts.Config.OpenCodePath) == "" {
		return errors.New("OpenCodePath is required")
	}
	if strings.TrimSpace(opts.Config.DefaultDirectory) == "" {
		return errors.New("DefaultDirectory is required")
	}

	writeStatus(opts.ConfigDir, "")

	upstream := fmt.Sprintf("http://127.0.0.1:%d", opts.Config.OpenCodePort)
	listenAddr := decideGatewayListenAddr(ctx, opts.Config, opts.Tailscale, opts.ConfigDir)

	gw, err := gateway.New(gateway.Options{
		ListenAddr: listenAddr,
		Upstream:   upstream,
		Token:      opts.Token,
	})
	if err != nil {
		writeStatus(opts.ConfigDir, "gateway: "+err.Error())
		return err
	}
	defer func() { _ = gw.Close() }()

	var wg sync.WaitGroup
	errCh := make(chan error, 2)

	wg.Add(1)
	go func() {
		defer wg.Done()
		if err := gw.Start(ctx); err != nil {
			errCh <- fmt.Errorf("gateway: %w", err)
		}
	}()

	wg.Add(1)
	go func() {
		defer wg.Done()
		if err := superviseOpenCode(ctx, opts.ConfigDir, opts.Config.OpenCodePath, opts.Config.OpenCodePort, opts.Config.DefaultDirectory); err != nil {
			errCh <- err
		}
	}()

	select {
	case <-ctx.Done():
		wg.Wait()
		return nil
	case err := <-errCh:
		writeStatus(opts.ConfigDir, err.Error())
		cancel()
		wg.Wait()
		return err
	}
}

func decideGatewayListenAddr(ctx context.Context, cfg config.Config, ts tailscale.Client, configDir string) string {
	switch cfg.Mode {
	case config.ModeLAN:
		return fmt.Sprintf("0.0.0.0:%d", cfg.GatewayPort)
	case config.ModeLocalhost:
		return fmt.Sprintf("127.0.0.1:%d", cfg.GatewayPort)
	case config.ModeTailscale:
		st, err := ts.GetStatus(ctx)
		if err != nil {
			// In LaunchAgent environments, the Tailscale macOS bundle binary can fail to emit JSON even
			// when Tailscale is running. In that specific case, fall back to a best-effort interface
			// scan so iPhone can still reach the gateway via the tailnet IP.
			if errors.Is(err, tailscale.ErrStatusUnreadable) {
				if ips := cgnatIPv4s(); len(ips) > 0 {
					writeStatus(configDir, fmt.Sprintf("tailscale: status unreadable; binding gateway to %s", ips[0]))
					fmt.Fprintln(os.Stderr, "oc-pocket: tailscale status unreadable; binding gateway to "+ips[0])
					return fmt.Sprintf("%s:%d", ips[0], cfg.GatewayPort)
				}
			}
			writeStatus(configDir, "tailscale: "+err.Error())
			return fmt.Sprintf("127.0.0.1:%d", cfg.GatewayPort)
		}
		if st.DNSName != "" {
			configured, _ := ts.TryConfigureServe(ctx, cfg.GatewayPort)
			if configured {
				return fmt.Sprintf("127.0.0.1:%d", cfg.GatewayPort)
			}
		} else {
			writeStatus(configDir, "tailscale: DNS name unavailable (MagicDNS may be disabled); binding to Tailscale IPv4 instead of Serve URL")
		}
		ipv4 := st.IPv4
		if ipv4 == "" {
			if ips := cgnatIPv4s(); len(ips) > 0 {
				ipv4 = ips[0]
			}
		}
		if ipv4 != "" {
			return fmt.Sprintf("%s:%d", ipv4, cfg.GatewayPort)
		}
		writeStatus(configDir, "tailscale: could not determine IPv4 for fallback")
		return fmt.Sprintf("127.0.0.1:%d", cfg.GatewayPort)
	default:
		return fmt.Sprintf("127.0.0.1:%d", cfg.GatewayPort)
	}
}

func superviseOpenCode(ctx context.Context, configDir string, opencodePath string, port int, defaultDirectory string) error {
	backoff := 500 * time.Millisecond
	for {
		if ctx.Err() != nil {
			return nil
		}

		cmd := exec.Command(opencodePath, "serve", "--hostname", "127.0.0.1", "--port", strconv.Itoa(port))
		cmd.Dir = defaultDirectory
		cmd.Stdout = os.Stdout
		cmd.Stderr = os.Stderr
		cmd.Env = os.Environ()

		if err := cmd.Start(); err != nil {
			writeStatus(configDir, "opencode start: "+err.Error())
			return err
		}

		waitCh := make(chan error, 1)
		go func() { waitCh <- cmd.Wait() }()

		select {
		case <-ctx.Done():
			_ = cmd.Process.Kill()
			<-waitCh
			return nil
		case err := <-waitCh:
			// Restart on unexpected exit.
			if ctx.Err() != nil {
				return nil
			}
			writeStatus(configDir, "opencode exited: "+exitErrorString(err))
			time.Sleep(backoff)
			if backoff < 10*time.Second {
				backoff *= 2
			}
		}
	}
}

func exitErrorString(err error) string {
	if err == nil {
		return "ok"
	}
	return err.Error()
}
