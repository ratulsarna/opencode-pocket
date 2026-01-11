package netutil_test

import (
	"testing"

	"github.com/ratulsarna/opencode-pocket/companion/oc-pocket/internal/netutil"
)

func TestDecideTailscaleBaseURL_ServeConfigured_UsesServeURL(t *testing.T) {
	t.Parallel()

	d := netutil.DecideTailscaleBaseURL(4096, "https://my.ts.net", "100.1.2.3", true, "my.ts.net")
	if d.BaseURL != "https://my.ts.net" {
		t.Fatalf("BaseURL: got=%q", d.BaseURL)
	}
}

func TestDecideTailscaleBaseURL_ServeConfigured_FallsBackToDNSName(t *testing.T) {
	t.Parallel()

	d := netutil.DecideTailscaleBaseURL(4096, "", "100.1.2.3", true, "my.ts.net")
	if d.BaseURL != "https://my.ts.net" {
		t.Fatalf("BaseURL: got=%q", d.BaseURL)
	}
}

func TestDecideTailscaleBaseURL_NotServeConfigured_FallsBackToIPv4(t *testing.T) {
	t.Parallel()

	d := netutil.DecideTailscaleBaseURL(4096, "https://my.ts.net", "100.1.2.3", false, "my.ts.net")
	if d.BaseURL != "http://100.1.2.3:4096" {
		t.Fatalf("BaseURL: got=%q", d.BaseURL)
	}
}

func TestDecideTailscaleBaseURL_ServeConfigured_InvalidServeURL_IsRejected(t *testing.T) {
	t.Parallel()

	d := netutil.DecideTailscaleBaseURL(4096, "https://", "100.1.2.3", true, "")
	if d.BaseURL != "" {
		t.Fatalf("BaseURL: got=%q", d.BaseURL)
	}
	if d.Warn == "" {
		t.Fatalf("expected warning")
	}
}
