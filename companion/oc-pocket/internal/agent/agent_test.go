package agent

import (
	"context"
	"os"
	"path/filepath"
	"testing"

	"github.com/ratulsarna/opencode-pocket/companion/oc-pocket/internal/config"
	"github.com/ratulsarna/opencode-pocket/companion/oc-pocket/internal/tailscale"
)

type fakeRunner struct {
	run func(name string, args ...string) (string, string, int, error)
}

func (f fakeRunner) Run(_ context.Context, name string, args ...string) (string, string, int, error) {
	return f.run(name, args...)
}

func TestDecideGatewayListenAddr_Tailscale_StatusError_UsesCGNATFallback(t *testing.T) {
	prev := cgnatIPv4s
	t.Cleanup(func() { cgnatIPv4s = prev })
	cgnatIPv4s = func() []string { return []string{"100.64.0.1"} }

	cfg := config.Config{Mode: config.ModeTailscale, GatewayPort: 4096}
	ts := tailscale.Client{Runner: fakeRunner{
		run: func(_ string, _ ...string) (string, string, int, error) {
			// Simulate the LaunchAgent environment where the Tailscale bundle binary emits a
			// non-JSON error message to stdout.
			return "The Tailscale GUI failed to start.", "", 0, nil
		},
	}}

	dir := t.TempDir()
	got := decideGatewayListenAddr(context.Background(), cfg, ts, dir)
	if got != "100.64.0.1:4096" {
		t.Fatalf("listen addr: got=%q want=%q", got, "100.64.0.1:4096")
	}

	if _, err := os.Stat(filepath.Join(dir, "status.json")); err != nil {
		t.Fatalf("expected status.json to exist: %v", err)
	}
}

func TestDecideGatewayListenAddr_Tailscale_StatusError_NoFallback_WritesStatusAndUsesLoopback(t *testing.T) {
	prev := cgnatIPv4s
	t.Cleanup(func() { cgnatIPv4s = prev })
	cgnatIPv4s = func() []string { return nil }

	cfg := config.Config{Mode: config.ModeTailscale, GatewayPort: 4096}
	ts := tailscale.Client{Runner: fakeRunner{
		run: func(_ string, _ ...string) (string, string, int, error) {
			return "The Tailscale GUI failed to start.", "", 0, nil
		},
	}}

	dir := t.TempDir()
	got := decideGatewayListenAddr(context.Background(), cfg, ts, dir)
	if got != "127.0.0.1:4096" {
		t.Fatalf("listen addr: got=%q want=%q", got, "127.0.0.1:4096")
	}
	if _, err := os.Stat(filepath.Join(dir, "status.json")); err != nil {
		t.Fatalf("expected status.json to exist: %v", err)
	}
}

func TestDecideGatewayListenAddr_Tailscale_NotLoggedIn_DoesNotUseCGNATFallback(t *testing.T) {
	prev := cgnatIPv4s
	t.Cleanup(func() { cgnatIPv4s = prev })
	cgnatIPv4s = func() []string { return []string{"100.64.0.1"} }

	cfg := config.Config{Mode: config.ModeTailscale, GatewayPort: 4096}
	ts := tailscale.Client{Runner: fakeRunner{
		run: func(_ string, _ ...string) (string, string, int, error) {
			// This should not be treated as the JSON-parse failure case.
			return `{"BackendState":"Stopped","Self":null}`, "", 0, nil
		},
	}}

	dir := t.TempDir()
	got := decideGatewayListenAddr(context.Background(), cfg, ts, dir)
	if got != "127.0.0.1:4096" {
		t.Fatalf("listen addr: got=%q want=%q", got, "127.0.0.1:4096")
	}
	if _, err := os.Stat(filepath.Join(dir, "status.json")); err != nil {
		t.Fatalf("expected status.json to exist: %v", err)
	}
}
