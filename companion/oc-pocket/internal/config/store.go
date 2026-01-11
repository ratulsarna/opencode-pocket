package config

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
)

type Mode string

const (
	ModeTailscale Mode = "tailscale"
	ModeLAN       Mode = "lan"
	ModeLocalhost Mode = "localhost"
)

type Config struct {
	Mode             Mode   `json:"mode"`
	GatewayPort      int    `json:"gatewayPort"`
	OpenCodePort     int    `json:"openCodePort"`
	OpenCodePath     string `json:"openCodePath"`
	DefaultDirectory string `json:"defaultDirectory"`
}

type Store struct {
	BaseDir string
}

func (s Store) Save(cfg Config, token string) error {
	if s.BaseDir == "" {
		return errors.New("BaseDir is required")
	}
	if cfg.GatewayPort == 0 {
		return errors.New("GatewayPort is required")
	}
	if cfg.OpenCodePort == 0 {
		return errors.New("OpenCodePort is required")
	}
	if cfg.OpenCodePath == "" {
		return errors.New("OpenCodePath is required")
	}
	if cfg.DefaultDirectory == "" {
		return errors.New("DefaultDirectory is required")
	}
	if err := os.MkdirAll(s.BaseDir, 0o700); err != nil {
		return err
	}

	configPath := filepath.Join(s.BaseDir, "config.json")
	tokenPath := filepath.Join(s.BaseDir, "token")

	raw, err := json.MarshalIndent(cfg, "", "  ")
	if err != nil {
		return err
	}
	if err := atomicWriteFile(configPath, raw, 0o600); err != nil {
		return fmt.Errorf("write config: %w", err)
	}
	if err := atomicWriteFile(tokenPath, []byte(token), 0o600); err != nil {
		return fmt.Errorf("write token: %w", err)
	}
	return nil
}

func (s Store) Load() (Config, string, error) {
	if s.BaseDir == "" {
		return Config{}, "", errors.New("BaseDir is required")
	}
	configPath := filepath.Join(s.BaseDir, "config.json")
	tokenPath := filepath.Join(s.BaseDir, "token")

	raw, err := os.ReadFile(configPath)
	if err != nil {
		return Config{}, "", err
	}
	var cfg Config
	if err := json.Unmarshal(raw, &cfg); err != nil {
		return Config{}, "", err
	}

	tokenRaw, err := os.ReadFile(tokenPath)
	if err != nil {
		return Config{}, "", err
	}
	return cfg, string(tokenRaw), nil
}

func atomicWriteFile(path string, contents []byte, perm os.FileMode) error {
	dir := filepath.Dir(path)
	tmp, err := os.CreateTemp(dir, filepath.Base(path)+".tmp-*")
	if err != nil {
		return err
	}
	tmpName := tmp.Name()
	defer func() { _ = os.Remove(tmpName) }()

	if err := tmp.Chmod(perm); err != nil {
		_ = tmp.Close()
		return err
	}
	if _, err := tmp.Write(contents); err != nil {
		_ = tmp.Close()
		return err
	}
	if err := tmp.Sync(); err != nil {
		_ = tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	return os.Rename(tmpName, path)
}
