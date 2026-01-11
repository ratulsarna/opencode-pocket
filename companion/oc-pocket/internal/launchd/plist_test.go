package launchd_test

import (
	"strings"
	"testing"

	"github.com/ratulsarna/opencode-pocket/companion/oc-pocket/internal/launchd"
)

func TestRenderPlist_IncludesProgramArgumentsAndKeepAlive(t *testing.T) {
	t.Parallel()

	b, err := launchd.RenderPlist(launchd.PlistOptions{
		Label:       "com.ratulsarna.oc-pocket",
		Program:     "/abs/path/to/oc-pocket",
		ProgramArgs: []string{"agent"},
		RunAtLoad:   true,
		KeepAlive:   true,
	})
	if err != nil {
		t.Fatalf("RenderPlist() error: %v", err)
	}

	s := string(b)
	for _, want := range []string{
		"com.ratulsarna.oc-pocket",
		"/abs/path/to/oc-pocket",
		"agent",
		"RunAtLoad",
		"KeepAlive",
	} {
		if !strings.Contains(s, want) {
			t.Fatalf("plist missing %q:\n%s", want, s)
		}
	}
}
