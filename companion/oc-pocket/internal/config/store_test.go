package config_test

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/ratulsarna/opencode-pocket/companion/oc-pocket/internal/config"
)

func TestStore_SaveLoad_RoundTrip(t *testing.T) {
	t.Parallel()

	baseDir := t.TempDir()
	store := config.Store{BaseDir: baseDir}

	cfg := config.Config{
		Mode:         config.ModeTailscale,
		GatewayPort:  4096,
		OpenCodePort: 4097,
		OpenCodePath: "/usr/local/bin/opencode",
		DefaultDirectory: "/Users/example/work",
	}
	token := "tok_test"

	if err := store.Save(cfg, token); err != nil {
		t.Fatalf("Save() error: %v", err)
	}

	gotCfg, gotToken, err := store.Load()
	if err != nil {
		t.Fatalf("Load() error: %v", err)
	}
	if gotCfg != cfg {
		t.Fatalf("cfg mismatch: got=%+v want=%+v", gotCfg, cfg)
	}
	if gotToken != token {
		t.Fatalf("token mismatch: got=%q want=%q", gotToken, token)
	}

	// Token should be stored in a strict-perms file.
	info, err := os.Stat(filepath.Join(baseDir, "token"))
	if err != nil {
		t.Fatalf("stat token: %v", err)
	}
	if info.Mode().Perm() != 0o600 {
		t.Fatalf("token perms: got=%#o want=%#o", info.Mode().Perm(), 0o600)
	}
}
