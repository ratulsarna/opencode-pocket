package pairing_test

import (
	"strings"
	"testing"

	"github.com/ratulsarna/opencode-pocket/companion/oc-pocket/internal/pairing"
)

func TestEncodeDecode_RoundTrip(t *testing.T) {
	t.Parallel()

	payload := pairing.Payload{
		Version: 1,
		BaseURL: "https://example.ts.net",
		Token:   "tok_123",
		Name:    "My Mac",
	}

	s, err := pairing.Encode(payload)
	if err != nil {
		t.Fatalf("Encode() error: %v", err)
	}

	got, err := pairing.Decode(s)
	if err != nil {
		t.Fatalf("Decode() error: %v", err)
	}
	if got.BaseURL != payload.BaseURL || got.Token != payload.Token || got.Name != payload.Name || got.Version != payload.Version {
		t.Fatalf("payload mismatch: got=%+v want=%+v", got, payload)
	}
}

func TestDecode_InvalidPayload_ReturnsError(t *testing.T) {
	t.Parallel()

	_, err := pairing.Decode("not-a-valid-pairing-string")
	if err == nil {
		t.Fatalf("expected error")
	}
}

func TestGenerateToken_IsUrlSafeAndLongEnough(t *testing.T) {
	t.Parallel()

	tok, err := pairing.GenerateToken()
	if err != nil {
		t.Fatalf("GenerateToken() error: %v", err)
	}
	if len(tok) < 43 {
		t.Fatalf("token too short: %d", len(tok))
	}
	if strings.ContainsAny(tok, "+/=") {
		t.Fatalf("token not base64url-safe: %q", tok)
	}
}
