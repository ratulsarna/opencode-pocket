package tailscale

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
)

type CommandRunner interface {
	Run(ctx context.Context, name string, args ...string) (stdout string, stderr string, exitCode int, err error)
}

type Client struct {
	Runner CommandRunner
	// Binary optionally overrides the `tailscale` CLI path/name.
	// If empty, the client attempts to locate a suitable binary.
	Binary string
}

type Status struct {
	LoggedIn bool
	IPv4     string
	DNSName  string
}

var ErrStatusUnreadable = errors.New("tailscale status unreadable")

func (c Client) GetStatus(ctx context.Context) (Status, error) {
	if c.Runner == nil {
		return Status{}, errors.New("Runner is required")
	}

	cmd, userCmd := c.resolveBinary()
	stdout, stderr, _, err := c.Runner.Run(ctx, cmd, "status", "--json")
	if err != nil {
		return Status{}, actionableExecError(userCmd, err, stderr)
	}

	type selfInfo struct {
		DNSName      string   `json:"DNSName"`
		TailscaleIPs []string `json:"TailscaleIPs"`
	}
	type statusJSON struct {
		BackendState string    `json:"BackendState"`
		Self         *selfInfo `json:"Self"`
	}

	var sj statusJSON
	if jerr := json.Unmarshal([]byte(stdout), &sj); jerr != nil {
		// If JSON parsing fails, treat as not ready but actionable.
		return Status{}, fmt.Errorf("%w: tailscale is installed but status could not be read; try `%s status`: %v", ErrStatusUnreadable, userCmd, jerr)
	}

	if sj.BackendState != "Running" || sj.Self == nil {
		return Status{LoggedIn: false}, fmt.Errorf("tailscale is not running or not logged in; run `%s up`", userCmd)
	}

	ipv4 := ""
	for _, ip := range sj.Self.TailscaleIPs {
		if strings.Contains(ip, ".") {
			ipv4 = ip
			break
		}
	}
	return Status{
		LoggedIn: true,
		IPv4:     ipv4,
		DNSName:  strings.TrimSuffix(sj.Self.DNSName, "."),
	}, nil
}

// TryConfigureServe attempts to configure Tailscale Serve for the given local port.
// It is best-effort: on failure it returns (false, nil) so callers can fall back
// to binding directly to the Tailscale interface/IP.
func (c Client) TryConfigureServe(ctx context.Context, port int) (configured bool, err error) {
	if c.Runner == nil {
		return false, errors.New("Runner is required")
	}
	if port <= 0 {
		return false, errors.New("port must be > 0")
	}

	cmd, userCmd := c.resolveBinary()

	// Best-effort, version-tolerant behavior:
	// - If `tailscale serve` exists and supports `--bg`, attempt: `tailscale serve --bg <port>`
	// - Otherwise return not configured; caller will use fallback.
	helpOut, helpErr, _, helpRunErr := c.Runner.Run(ctx, cmd, "serve", "--help")
	if helpRunErr != nil {
		return false, actionableExecError(userCmd, helpRunErr, helpErr)
	}

	args := []string{"serve"}
	if strings.Contains(helpOut, "--yes") {
		args = append(args, "--yes")
	}
	if strings.Contains(helpOut, "--bg") {
		args = append(args, "--bg")
	} else {
		return false, nil
	}
	args = append(args, fmt.Sprintf("%d", port))

	_, _, _, runErr := c.Runner.Run(ctx, cmd, args...)
	if runErr != nil {
		// Not configured; caller will fall back to binding to the tailnet IP.
		return false, nil
	}

	return true, nil
}

func (c Client) resolveBinary() (runCmd string, userCmd string) {
	if strings.TrimSpace(c.Binary) != "" {
		return c.Binary, c.Binary
	}

	// Prefer PATH `tailscale` so error messages match typical docs.
	if _, err := exec.LookPath("tailscale"); err == nil {
		return "tailscale", "tailscale"
	}

	// macOS: Tailscale.app bundles a CLI binary named "Tailscale" (capital T) at a stable path.
	if runtime.GOOS == "darwin" {
		candidates := []string{
			"/Applications/Tailscale.app/Contents/MacOS/Tailscale",
		}
		if home, err := os.UserHomeDir(); err == nil && strings.TrimSpace(home) != "" {
			candidates = append(candidates, filepath.Join(home, "Applications", "Tailscale.app", "Contents", "MacOS", "Tailscale"))
		}

		for _, p := range candidates {
			if isExecutableFile(p) {
				return p, p
			}
		}
	}

	// Last-ditch fallback: some environments may expose a capitalized name.
	if _, err := exec.LookPath("Tailscale"); err == nil {
		return "Tailscale", "Tailscale"
	}

	return "tailscale", "tailscale"
}

func isExecutableFile(path string) bool {
	fi, err := os.Stat(path)
	if err != nil || fi.IsDir() {
		return false
	}
	return fi.Mode()&0o111 != 0
}

func actionableExecError(userCmd string, err error, stderr string) error {
	msg := err.Error()
	if strings.Contains(msg, "executable file not found") || strings.Contains(msg, "not found") {
		if userCmd != "tailscale" {
				return fmt.Errorf("tailscale CLI is not available (%s); install or re-install Tailscale and re-run `oc-pocket setup`", userCmd)
		}
			return errors.New("tailscale is not installed (or not on PATH); install Tailscale and re-run `oc-pocket setup`")
	}
	if strings.Contains(stderr, "Logged out") || strings.Contains(stderr, "not logged in") {
			return fmt.Errorf("tailscale is installed but not logged in; run `%s up` and re-run `oc-pocket setup`", userCmd)
	}
	return err
}
