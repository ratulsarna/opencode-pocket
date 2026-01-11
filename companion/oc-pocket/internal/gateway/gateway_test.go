package gateway_test

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/ratulsarna/opencode-pocket/companion/oc-pocket/internal/gateway"
)

func TestGateway_AuthEnforced_AndAuthorizationNotForwarded(t *testing.T) {
	t.Parallel()

	var sawAuthHeader string
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		sawAuthHeader = r.Header.Get("Authorization")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok"))
	}))
	t.Cleanup(upstream.Close)

	gw, err := gateway.New(gateway.Options{
		ListenAddr: "127.0.0.1:0",
		Upstream:   upstream.URL,
		Token:      "tok",
	})
	if err != nil {
		t.Fatalf("New() error: %v", err)
	}
	t.Cleanup(func() { _ = gw.Close() })

	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	go func() { _ = gw.Start(ctx) }()

	client := &http.Client{Timeout: 2 * time.Second}

	// No auth -> 401.
	resp, err := client.Get(gw.BaseURL() + "/hello")
	if err != nil {
		t.Fatalf("GET (no auth) error: %v", err)
	}
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("status (no auth): got=%d want=%d", resp.StatusCode, http.StatusUnauthorized)
	}

	// Correct auth -> 200 and upstream doesn't see Authorization header.
	req, _ := http.NewRequest("GET", gw.BaseURL()+"/hello", nil)
	req.Header.Set("Authorization", "Bearer tok")
	resp, err = client.Do(req)
	if err != nil {
		t.Fatalf("Do() error: %v", err)
	}
	body, _ := io.ReadAll(resp.Body)
	_ = resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status: got=%d want=%d body=%q", resp.StatusCode, http.StatusOK, string(body))
	}
	if strings.TrimSpace(string(body)) != "ok" {
		t.Fatalf("body: got=%q want=%q", string(body), "ok")
	}
	if sawAuthHeader != "" {
		t.Fatalf("Authorization header forwarded upstream: %q", sawAuthHeader)
	}
}

func TestGateway_SSE_IsStreamed(t *testing.T) {
	t.Parallel()

	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		w.Header().Set("Cache-Control", "no-cache")
		w.Header().Set("Connection", "keep-alive")

		flusher, ok := w.(http.Flusher)
		if !ok {
			t.Fatalf("expected http.Flusher")
		}

		_, _ = fmt.Fprint(w, "data: first\n\n")
		flusher.Flush()
		time.Sleep(250 * time.Millisecond)
		_, _ = fmt.Fprint(w, "data: second\n\n")
		flusher.Flush()
	}))
	t.Cleanup(upstream.Close)

	gw, err := gateway.New(gateway.Options{
		ListenAddr: "127.0.0.1:0",
		Upstream:   upstream.URL,
		Token:      "tok",
	})
	if err != nil {
		t.Fatalf("New() error: %v", err)
	}
	t.Cleanup(func() { _ = gw.Close() })

	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	go func() { _ = gw.Start(ctx) }()

	req, _ := http.NewRequest("GET", gw.BaseURL()+"/events", nil)
	req.Header.Set("Authorization", "Bearer tok")

	client := &http.Client{Timeout: 3 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		t.Fatalf("Do() error: %v", err)
	}
	t.Cleanup(func() { _ = resp.Body.Close() })

	reader := bufio.NewReader(resp.Body)

	start := time.Now()
	line, err := reader.ReadString('\n')
	if err != nil {
		t.Fatalf("read first line: %v", err)
	}
	if !strings.HasPrefix(line, "data: first") {
		t.Fatalf("unexpected first line: %q", line)
	}
	if time.Since(start) > 200*time.Millisecond {
		t.Fatalf("first SSE chunk arrived too late: %v", time.Since(start))
	}
}
