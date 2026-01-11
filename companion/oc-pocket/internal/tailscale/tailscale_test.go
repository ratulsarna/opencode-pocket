package tailscale_test

import (
	"context"
	"errors"
	"testing"

	"github.com/ratulsarna/opencode-pocket/companion/oc-pocket/internal/tailscale"
)

type fakeRunner struct {
	run func(name string, args ...string) (string, string, int, error)
}

func (f fakeRunner) Run(_ context.Context, name string, args ...string) (string, string, int, error) {
	return f.run(name, args...)
}

func TestGetStatus_MissingBinary_ReturnsActionableError(t *testing.T) {
	t.Parallel()

	client := tailscale.Client{Runner: fakeRunner{
		run: func(_ string, _ ...string) (string, string, int, error) {
			return "", "", 127, errors.New("exec: \"tailscale\": executable file not found in $PATH")
		},
	}}

	_, err := client.GetStatus(context.Background())
	if err == nil {
		t.Fatalf("expected error")
	}
}
