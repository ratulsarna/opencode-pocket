package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/mdp/qrterminal/v3"

	"github.com/ratulsarna/opencode-pocket/companion/oc-pocket/internal/agent"
	"github.com/ratulsarna/opencode-pocket/companion/oc-pocket/internal/config"
	"github.com/ratulsarna/opencode-pocket/companion/oc-pocket/internal/executil"
	"github.com/ratulsarna/opencode-pocket/companion/oc-pocket/internal/launchd"
	"github.com/ratulsarna/opencode-pocket/companion/oc-pocket/internal/netutil"
	"github.com/ratulsarna/opencode-pocket/companion/oc-pocket/internal/ocmobile"
	"github.com/ratulsarna/opencode-pocket/companion/oc-pocket/internal/pairing"
	"github.com/ratulsarna/opencode-pocket/companion/oc-pocket/internal/tailscale"
)

func main() {
	os.Exit(run(os.Args[1:]))
}

func run(args []string) int {
	if len(args) == 0 || args[0] == "-h" || args[0] == "--help" || args[0] == "help" {
		printUsage()
		return 0
	}

	switch args[0] {
	case "setup":
		return cmdSetup(args[1:])
	case "status":
		return cmdStatus(args[1:])
	case "restart":
		return cmdRestart(args[1:])
	case "uninstall":
		return cmdUninstall(args[1:])
	case "token":
		return cmdToken(args[1:])
	case "agent":
		return cmdAgent(args[1:])
	default:
		fmt.Fprintf(os.Stderr, "Unknown command: %s\n\n", args[0])
		printUsage()
		return 2
	}
}

func printUsage() {
	fmt.Println("oc-pocket (macOS companion CLI)")
	fmt.Println()
	fmt.Println("Usage:")
	fmt.Println("  oc-pocket setup")
	fmt.Println("  oc-pocket status")
	fmt.Println("  oc-pocket restart")
	fmt.Println("  oc-pocket uninstall")
	fmt.Println("  oc-pocket token rotate")
	fmt.Println()
	fmt.Println("Internal:")
	fmt.Println("  oc-pocket agent")
	fmt.Println()
}

func cmdSetup(args []string) int {
	fs := flag.NewFlagSet("setup", flag.ContinueOnError)
	modeFlag := fs.String("mode", "", "tailscale|lan|localhost (if empty, prompt)")
	configDirFlag := fs.String("config-dir", "", "override config dir (advanced)")
	opencodePathFlag := fs.String("opencode-path", "", "path to `opencode` binary (optional)")
	defaultDirFlag := fs.String("default-dir", "", "directory to start OpenCode in (optional; defaults to a safe oc-pocket workdir)")
	skipLaunchdFlag := fs.Bool("skip-launchd", false, "do not install/run LaunchAgent (advanced)")
	if err := fs.Parse(args); err != nil {
		if errors.Is(err, flag.ErrHelp) {
			return 0
		}
		fmt.Fprintln(os.Stderr, err.Error())
		return 2
	}

	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()

	mode, err := resolveMode(*modeFlag)
	if err != nil {
		fmt.Fprintln(os.Stderr, err.Error())
		return 2
	}

	configDir, err := ocmobile.ConfigDir(*configDirFlag)
	if err != nil {
		fmt.Fprintln(os.Stderr, err.Error())
		return 1
	}

	opencodePath, err := resolveOpenCodePath(*opencodePathFlag)
	if err != nil {
		fmt.Fprintln(os.Stderr, err.Error())
		return 1
	}

	defaultDirectory, err := resolveDefaultDirectory(*defaultDirFlag, configDir)
	if err != nil {
		fmt.Fprintln(os.Stderr, err.Error())
		return 1
	}

	if mode == config.ModeTailscale {
		ts := tailscale.Client{Runner: executil.NewRunner()}
		if _, err := ts.GetStatus(ctx); err != nil {
			fmt.Fprintln(os.Stderr, "Tailscale is required for this mode.")
			fmt.Fprintln(os.Stderr, err.Error())
			fmt.Fprintln(os.Stderr, "Fix: install and log in to Tailscale, then re-run `oc-pocket setup`.")
			return 1
		}
	}

	// Prefer reusing an existing token so re-running `setup` does not force re-pairing.
	// Use `oc-pocket token rotate` if you intentionally want a new token.
	token := ""
	if raw, err := os.ReadFile(filepath.Join(configDir, "token")); err == nil {
		token = strings.TrimSpace(string(raw))
	}
	if token == "" {
		var err error
		token, err = pairing.GenerateToken()
		if err != nil {
			fmt.Fprintln(os.Stderr, err.Error())
			return 1
		}
	}

	cfg := config.Config{
		Mode:             mode,
		GatewayPort:      ocmobile.DefaultGatewayPort,
		OpenCodePort:     ocmobile.DefaultOpenCodePort,
		OpenCodePath:     opencodePath,
		DefaultDirectory: defaultDirectory,
	}

	store := config.Store{BaseDir: configDir}
	if err := store.Save(cfg, token); err != nil {
		fmt.Fprintln(os.Stderr, err.Error())
		return 1
	}

	// For OSS users installing from GitHub Releases, oc-pocket won't live inside a git repo.
	// If we're inside the repo, build a stable binary at companion/oc-pocket/bin/oc-pocket for launchd.
	// Otherwise, use the currently running executable.
	binPath := ""
	if repoRoot, err := ocmobile.FindRepoRoot(); err == nil {
		if err := buildRepoBinary(ctx, repoRoot); err != nil {
			fmt.Fprintln(os.Stderr, err.Error())
			return 1
		}
		binPath = ocmobile.RepoBinaryPath(repoRoot)
	} else {
		exe, err := os.Executable()
		if err != nil || strings.TrimSpace(exe) == "" {
			fmt.Fprintln(os.Stderr, "Could not determine oc-pocket executable path.")
			return 1
		}
		binPath = exe
	}

	stdoutPath := filepath.Join(configDir, "agent.stdout.log")
	stderrPath := filepath.Join(configDir, "agent.stderr.log")
	plistBytes, err := launchd.RenderPlist(launchd.PlistOptions{
		Label:       ocmobile.LaunchAgentLabel,
		Program:     binPath,
		ProgramArgs: []string{"agent", "--config-dir", configDir},
		RunAtLoad:   true,
		KeepAlive:   true,
		StdoutPath:  stdoutPath,
		StderrPath:  stderrPath,
	})
	if err != nil {
		fmt.Fprintln(os.Stderr, err.Error())
		return 1
	}

	exitCode := 0
	if !*skipLaunchdFlag {
		plistPath, err := ocmobile.LaunchAgentPlistPath()
		if err != nil {
			fmt.Fprintln(os.Stderr, err.Error())
			return 1
		}
		if err := os.MkdirAll(filepath.Dir(plistPath), 0o755); err != nil {
			fmt.Fprintln(os.Stderr, err.Error())
			return 1
		}
		if err := os.WriteFile(plistPath, plistBytes, 0o644); err != nil {
			fmt.Fprintln(os.Stderr, err.Error())
			return 1
		}

		if err := launchctlBootstrap(ctx, plistPath); err != nil {
			exitCode = 1
			fmt.Fprintln(os.Stderr, err.Error())
			fmt.Fprintln(os.Stderr, "")
			fmt.Fprintln(os.Stderr, "LaunchAgent install failed. You can still run the agent manually in a separate terminal:")
			fmt.Fprintln(os.Stderr, "  "+binPath+" agent --config-dir "+strconv.Quote(configDir))
			fmt.Fprintln(os.Stderr, "")
			fmt.Fprintln(os.Stderr, "Or re-run setup with --skip-launchd to avoid launchctl entirely.")
		}
	} else {
		plistPath := filepath.Join(configDir, "com.ratulsarna.oc-pocket.plist")
		if err := os.WriteFile(plistPath, plistBytes, 0o644); err != nil {
			fmt.Fprintln(os.Stderr, err.Error())
			return 1
		}
		fmt.Println("LaunchAgent not installed (skip-launchd). Plist written to:")
		fmt.Println("  " + plistPath)
	}

	baseURL, extraURLs, warn := computePairingBaseURL(ctx, cfg.Mode, cfg.GatewayPort)
	if warn != "" {
		fmt.Fprintln(os.Stderr, warn)
	}

	payload, err := pairing.Encode(pairing.Payload{
		Version: 1,
		BaseURL: baseURL,
		Token:   token,
		Name:    ocmobile.DefaultDeviceName(),
	})
	if err != nil {
		fmt.Fprintln(os.Stderr, err.Error())
		return 1
	}

	fmt.Println()
	fmt.Println("Pair with iPhone:")
	fmt.Println()
	fmt.Println("Pairing string (copy/paste):")
	fmt.Println("  " + payload)
	fmt.Println()
	fmt.Println("Token:")
	fmt.Println("  " + token)
	fmt.Println()
	fmt.Println("Base URL:")
	fmt.Println("  " + baseURL)
	if len(extraURLs) > 0 {
		fmt.Println()
		fmt.Println("Other candidate URLs:")
		for _, u := range extraURLs {
			fmt.Println("  " + u)
		}
	}
	fmt.Println()
	fmt.Println("QR code:")
	qrterminal.GenerateHalfBlock(payload, qrterminal.L, os.Stdout)
	fmt.Println()
	fmt.Println("Next: open OpenCode Pocket on iPhone and scan the QR or paste the pairing string.")
	return exitCode
}

func cmdAgent(args []string) int {
	fs := flag.NewFlagSet("agent", flag.ContinueOnError)
	configDirFlag := fs.String("config-dir", "", "override config dir")
	if err := fs.Parse(args); err != nil {
		if errors.Is(err, flag.ErrHelp) {
			return 0
		}
		fmt.Fprintln(os.Stderr, err.Error())
		return 2
	}

	configDir, err := ocmobile.ConfigDir(*configDirFlag)
	if err != nil {
		fmt.Fprintln(os.Stderr, err.Error())
		return 1
	}

	store := config.Store{BaseDir: configDir}
	cfg, token, err := store.Load()
	if err != nil {
		fmt.Fprintln(os.Stderr, err.Error())
		return 1
	}

	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()

	runner := executil.NewRunner()
	ts := tailscale.Client{Runner: runner}

	if err := agent.Run(ctx, agent.Options{
		ConfigDir: configDir,
		Config:    cfg,
		Token:     token,
		Tailscale: ts,
	}); err != nil {
		fmt.Fprintln(os.Stderr, err.Error())
		return 1
	}
	return 0
}

func cmdStatus(args []string) int {
	fs := flag.NewFlagSet("status", flag.ContinueOnError)
	configDirFlag := fs.String("config-dir", "", "override config dir (advanced)")
	if err := fs.Parse(args); err != nil {
		if errors.Is(err, flag.ErrHelp) {
			return 0
		}
		fmt.Fprintln(os.Stderr, err.Error())
		return 2
	}

	configDir, err := ocmobile.ConfigDir(*configDirFlag)
	if err != nil {
		fmt.Fprintln(os.Stderr, err.Error())
		return 1
	}

	store := config.Store{BaseDir: configDir}
	cfg, _, err := store.Load()
	if err != nil {
		fmt.Fprintln(os.Stderr, "Not set up yet. Run: oc-pocket setup")
		return 1
	}

	fmt.Println("Config:")
	fmt.Println("  mode:", cfg.Mode)
	fmt.Println("  gatewayPort:", cfg.GatewayPort)
	fmt.Println("  openCodePort:", cfg.OpenCodePort)
	fmt.Println("  openCodePath:", cfg.OpenCodePath)
	fmt.Println("  defaultDirectory:", cfg.DefaultDirectory)

	running, state := launchctlPrint(context.Background())
	fmt.Println()
	fmt.Println("LaunchAgent:", ocmobile.LaunchAgentLabel)
	if running {
		fmt.Println("  state: running")
	} else {
		fmt.Println("  state:", state)
	}

	if status, ok := agent.ReadStatus(configDir); ok {
		fmt.Println()
		fmt.Println("Last status:")
		fmt.Println("  updatedAt:", time.UnixMilli(status.UpdatedAtMs).Format(time.RFC3339))
		if status.LastError != "" {
			fmt.Println("  lastError:", status.LastError)
		} else {
			fmt.Println("  lastError: (none)")
		}
	}
	return 0
}

func cmdRestart(args []string) int {
	fs := flag.NewFlagSet("restart", flag.ContinueOnError)
	if err := fs.Parse(args); err != nil {
		if errors.Is(err, flag.ErrHelp) {
			return 0
		}
		fmt.Fprintln(os.Stderr, err.Error())
		return 2
	}

	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()

	if err := launchctlKickstart(ctx); err != nil {
		fmt.Fprintln(os.Stderr, err.Error())
		return 1
	}
	fmt.Println("Restarted:", ocmobile.LaunchAgentLabel)
	return 0
}

func cmdToken(args []string) int {
	if len(args) == 0 || args[0] == "-h" || args[0] == "--help" {
		fmt.Println("Usage: oc-pocket token rotate")
		return 0
	}
	switch args[0] {
	case "rotate":
		return cmdTokenRotate(args[1:])
	default:
		fmt.Fprintln(os.Stderr, "Unknown token subcommand:", args[0])
		return 2
	}
}

func cmdTokenRotate(args []string) int {
	fs := flag.NewFlagSet("token rotate", flag.ContinueOnError)
	configDirFlag := fs.String("config-dir", "", "override config dir (advanced)")
	if err := fs.Parse(args); err != nil {
		if errors.Is(err, flag.ErrHelp) {
			return 0
		}
		fmt.Fprintln(os.Stderr, err.Error())
		return 2
	}

	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()

	configDir, err := ocmobile.ConfigDir(*configDirFlag)
	if err != nil {
		fmt.Fprintln(os.Stderr, err.Error())
		return 1
	}

	store := config.Store{BaseDir: configDir}
	cfg, _, err := store.Load()
	if err != nil {
		fmt.Fprintln(os.Stderr, "Not set up yet. Run: oc-pocket setup")
		return 1
	}

	token, err := pairing.GenerateToken()
	if err != nil {
		fmt.Fprintln(os.Stderr, err.Error())
		return 1
	}
	if err := store.Save(cfg, token); err != nil {
		fmt.Fprintln(os.Stderr, err.Error())
		return 1
	}

	if err := launchctlKickstart(ctx); err != nil {
		fmt.Fprintln(os.Stderr, "Warning: could not restart oc-pocket agent. Run `oc-pocket restart` before using the new pairing string.")
		fmt.Fprintln(os.Stderr, "Warning:", err.Error())
	}

	baseURL, _, _ := computePairingBaseURL(ctx, cfg.Mode, cfg.GatewayPort)
	payload, err := pairing.Encode(pairing.Payload{
		Version: 1,
		BaseURL: baseURL,
		Token:   token,
		Name:    ocmobile.DefaultDeviceName(),
	})
	if err != nil {
		fmt.Fprintln(os.Stderr, err.Error())
		return 1
	}
	fmt.Println("New pairing string:")
	fmt.Println("  " + payload)
	fmt.Println()
	fmt.Println("QR code:")
	qrterminal.GenerateHalfBlock(payload, qrterminal.L, os.Stdout)
	return 0
}

func resolveMode(mode string) (config.Mode, error) {
	mode = strings.TrimSpace(strings.ToLower(mode))
	if mode == "" {
		return promptMode()
	}
	switch mode {
	case string(config.ModeTailscale):
		return config.ModeTailscale, nil
	case string(config.ModeLAN):
		return config.ModeLAN, nil
	case string(config.ModeLocalhost):
		return config.ModeLocalhost, nil
	default:
		return "", fmt.Errorf("invalid --mode %q (expected tailscale|lan|localhost)", mode)
	}
}

func promptMode() (config.Mode, error) {
	fmt.Println("How do you want to connect from your iPhone?")
	fmt.Println("  1) Private, works anywhere (Recommended): Tailscale")
	fmt.Println("  2) On my Wi‑Fi only: Local Network")
	fmt.Println("  3) Localhost only (Developer)")
	fmt.Print("Choose 1-3: ")
	var input string
	if _, err := fmt.Fscanln(os.Stdin, &input); err != nil {
		return "", err
	}
	input = strings.TrimSpace(input)
	switch input {
	case "1":
		return config.ModeTailscale, nil
	case "2":
		return config.ModeLAN, nil
	case "3":
		return config.ModeLocalhost, nil
	default:
		return "", errors.New("invalid selection")
	}
}

func resolveOpenCodePath(flagValue string) (string, error) {
	if strings.TrimSpace(flagValue) != "" {
		p, err := filepath.Abs(flagValue)
		if err != nil {
			return "", err
		}
		if err := assertExecutable(p); err != nil {
			return "", err
		}
		return p, nil
	}
	p, err := exec.LookPath("opencode")
	if err != nil {
		return "", errors.New("`opencode` not found on PATH; install OpenCode and re-run setup, or pass --opencode-path")
	}
	p, err = filepath.Abs(p)
	if err != nil {
		return "", err
	}
	if err := assertExecutable(p); err != nil {
		return "", err
	}
	return p, nil
}

func assertExecutable(path string) error {
	info, err := os.Stat(path)
	if err != nil {
		return err
	}
	if info.IsDir() {
		return fmt.Errorf("%s is a directory", path)
	}
	if info.Mode()&0o111 == 0 {
		return fmt.Errorf("%s is not executable", path)
	}
	return nil
}

func buildRepoBinary(ctx context.Context, repoRoot string) error {
	target := ocmobile.RepoBinaryPath(repoRoot)
	moduleDir := ocmobile.GoModuleDir(repoRoot)
	if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
		return err
	}

	currentExe, _ := os.Executable()
	currentExe, _ = filepath.EvalSymlinks(currentExe)
	targetEval, _ := filepath.EvalSymlinks(target)
	if currentExe != "" && targetEval != "" && currentExe == targetEval {
		return nil
	}

	cmd := exec.CommandContext(ctx, "go", "build", "-o", target, ".")
	cmd.Dir = moduleDir
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	if err := cmd.Run(); err != nil {
		return fmt.Errorf("failed to build oc-pocket binary at %s: %w", target, err)
	}
	return nil
}

func computePairingBaseURL(ctx context.Context, mode config.Mode, gatewayPort int) (baseURL string, extraURLs []string, warn string) {
	switch mode {
	case config.ModeLAN:
		ips := netutil.LocalIPv4s()
		urls := make([]string, 0, len(ips))
		for _, ip := range ips {
			urls = append(urls, fmt.Sprintf("http://%s:%d", ip, gatewayPort))
		}
		if len(urls) == 0 {
			return fmt.Sprintf("http://127.0.0.1:%d", gatewayPort), nil, "Warning: could not detect a LAN IP; falling back to localhost."
		}
		return urls[0], urls[1:], ""

	case config.ModeTailscale:
		ts := tailscale.Client{Runner: executil.NewRunner()}
		st, err := ts.GetStatus(ctx)
		if err != nil {
			// Best-effort fallback: in some environments (notably LaunchAgent-like shells on macOS),
			// the Tailscale bundle binary can fail to emit JSON. If we can still detect a tailnet IP
			// locally, use it so iPhone pairing doesn't silently become localhost-only.
			if errors.Is(err, tailscale.ErrStatusUnreadable) {
				if ips := netutil.CGNATIPv4s(); len(ips) > 0 {
				base := fmt.Sprintf("http://%s:%d", ips[0], gatewayPort)
				warn = "Warning: Tailscale status could not be read; falling back to detected tailnet IP. " + err.Error()
				warn = warn + " " + fmt.Sprintf(
					"Warning: iOS may block this HTTP URL due to App Transport Security. Fix: enable Tailscale Serve to get an HTTPS Base URL "+
						"(try `tailscale serve --bg %d` on newer versions; otherwise run `tailscale serve --help` and serve localhost:%d), "+
						"then re-run `oc-pocket setup`, or choose option 2 (Local Network).",
					gatewayPort,
					gatewayPort,
				)
				return base, nil, warn
			}
			}
			return fmt.Sprintf("http://127.0.0.1:%d", gatewayPort), nil, "Warning: Tailscale not ready; falling back to localhost. " + err.Error()
		}
		serveBaseURL := ""
		serveConfigured := false
		if st.DNSName != "" {
			serveBaseURL = "https://" + st.DNSName
			serveConfigured, _ = ts.TryConfigureServe(ctx, gatewayPort)
		} else {
			warn = "Warning: Tailscale DNS name is unavailable (MagicDNS may be disabled); falling back to Tailscale IP."
		}

		d := netutil.DecideTailscaleBaseURL(gatewayPort, serveBaseURL, st.IPv4, serveConfigured, st.DNSName)
		if d.Warn != "" {
			if warn != "" {
				warn = warn + " " + d.Warn
			} else {
				warn = "Warning: " + d.Warn
			}
			}
			if strings.HasPrefix(d.BaseURL, "http://") {
				// iOS blocks non-HTTPS loads by default (ATS). When Tailscale Serve is not available, we fall back
				// to the tailnet IPv4 which uses HTTP; this may fail on iPhone unless using a Debug build that
				// allows arbitrary loads, or unless you choose LAN mode.
				suffix := fmt.Sprintf(
					"Warning: iOS may block this HTTP URL due to App Transport Security. Fix: enable Tailscale Serve to get an HTTPS Base URL "+
						"(try `tailscale serve --bg %d` on newer versions; otherwise run `tailscale serve --help` and serve localhost:%d), "+
						"then re-run `oc-pocket setup`, or choose option 2 (Local Network).",
					gatewayPort,
					gatewayPort,
				)
				if warn != "" {
					warn = warn + " " + suffix
				} else {
					warn = suffix
			}
		}
		if d.BaseURL == "" {
			return fmt.Sprintf("http://127.0.0.1:%d", gatewayPort), nil, warn
		}
		return d.BaseURL, nil, warn

	default:
		if mode == config.ModeLocalhost {
			return fmt.Sprintf("http://127.0.0.1:%d", gatewayPort), nil, "Localhost mode is for iOS Simulator / same-Mac testing only."
		}
		return fmt.Sprintf("http://127.0.0.1:%d", gatewayPort), nil, ""
	}
}

func resolveDefaultDirectory(flagValue string, configDir string) (string, error) {
	if strings.TrimSpace(flagValue) != "" {
		p, err := filepath.Abs(flagValue)
		if err != nil {
			return "", err
		}
		if err := assertDir(p); err != nil {
			return "", err
		}
		return p, nil
	}

	// Default to a dedicated workdir under oc-pocket's config directory so setup can be run
	// from anywhere without macOS prompting for Desktop/Documents/Downloads access.
	//
	// NOTE: Ensure configDir is created with restrictive permissions since it contains the auth token.
	if err := os.MkdirAll(configDir, 0o700); err != nil {
		return "", err
	}
	if err := os.Chmod(configDir, 0o700); err != nil {
		return "", err
	}

	workdir := filepath.Join(configDir, "workdir")
	if err := os.MkdirAll(workdir, 0o700); err != nil {
		return "", err
	}
	workdir, err := filepath.Abs(workdir)
	if err != nil {
		return "", err
	}
	if err := assertDir(workdir); err != nil {
		return "", err
	}
	return workdir, nil
}

func assertDir(path string) error {
	info, err := os.Stat(path)
	if err != nil {
		return err
	}
	if !info.IsDir() {
		return fmt.Errorf("%s is not a directory", path)
	}
	return nil
}

func launchctlBootstrap(ctx context.Context, plistPath string) error {
	uid := os.Getuid()
	domain := "gui/" + strconv.Itoa(uid)
	job := domain + "/" + ocmobile.LaunchAgentLabel

	_ = exec.CommandContext(ctx, "launchctl", "bootout", job).Run()

	if out, err := exec.CommandContext(ctx, "launchctl", "bootstrap", domain, plistPath).CombinedOutput(); err != nil {
		return fmt.Errorf("launchctl bootstrap failed: %w: %s", err, strings.TrimSpace(string(out)))
	}
	if out, err := exec.CommandContext(ctx, "launchctl", "kickstart", "-k", job).CombinedOutput(); err != nil {
		return fmt.Errorf("launchctl kickstart failed: %w: %s", err, strings.TrimSpace(string(out)))
	}
	return nil
}

func launchctlKickstart(ctx context.Context) error {
	uid := os.Getuid()
	job := "gui/" + strconv.Itoa(uid) + "/" + ocmobile.LaunchAgentLabel
	out, err := exec.CommandContext(ctx, "launchctl", "kickstart", "-k", job).CombinedOutput()
	if err != nil {
		return fmt.Errorf("launchctl kickstart failed: %w: %s", err, strings.TrimSpace(string(out)))
	}
	return nil
}

func launchctlPrint(ctx context.Context) (bool, string) {
	uid := os.Getuid()
	job := "gui/" + strconv.Itoa(uid) + "/" + ocmobile.LaunchAgentLabel
	out, err := exec.CommandContext(ctx, "launchctl", "print", job).CombinedOutput()
	if err != nil {
		return false, strings.TrimSpace(string(out))
	}
	s := string(out)
	if strings.Contains(s, "state = running") || strings.Contains(s, "state = waiting") {
		return true, "ok"
	}
	return false, "not running"
}

func cmdUninstall(args []string) int {
	fs := flag.NewFlagSet("uninstall", flag.ContinueOnError)
	purgeFlag := fs.Bool("purge", false, "also delete config directory (token, logs)")
	configDirFlag := fs.String("config-dir", "", "override config dir (advanced)")
	if err := fs.Parse(args); err != nil {
		if errors.Is(err, flag.ErrHelp) {
			return 0
		}
		fmt.Fprintln(os.Stderr, err.Error())
		return 2
	}

	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()

	uid := os.Getuid()
	job := "gui/" + strconv.Itoa(uid) + "/" + ocmobile.LaunchAgentLabel
	_ = exec.CommandContext(ctx, "launchctl", "bootout", job).Run()

	plistPath, err := ocmobile.LaunchAgentPlistPath()
	if err == nil {
		_ = os.Remove(plistPath)
	}

	if *purgeFlag {
		configDir, err := ocmobile.ConfigDir(*configDirFlag)
		if err != nil {
			fmt.Fprintln(os.Stderr, err.Error())
			return 1
		}
		_ = os.RemoveAll(configDir)
	}

	fmt.Println("Uninstalled:", ocmobile.LaunchAgentLabel)
	if *purgeFlag {
		fmt.Println("Purged config directory.")
	}
	return 0
}
