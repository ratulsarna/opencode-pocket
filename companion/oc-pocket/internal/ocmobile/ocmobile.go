package ocmobile

import (
	"errors"
	"os"
	"path/filepath"
)

const (
	LaunchAgentLabel    = "com.ratulsarna.oc-pocket"
	DefaultGatewayPort  = 4096
	DefaultOpenCodePort = 4097
	configDirName       = "oc-pocket"
)

func ConfigDir(override string) (string, error) {
	if override != "" {
		return filepath.Abs(override)
	}
	base, err := os.UserConfigDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(base, configDirName), nil
}

func LaunchAgentPlistPath() (string, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(home, "Library", "LaunchAgents", LaunchAgentLabel+".plist"), nil
}

func DefaultDeviceName() string {
	if h, err := os.Hostname(); err == nil && h != "" {
		return h
	}
	return "My Mac"
}

func FindRepoRoot() (string, error) {
	cwd, _ := os.Getwd()
	if cwd != "" {
		if root, ok := findRepoRootFrom(cwd); ok {
			return root, nil
		}
	}
	exe, _ := os.Executable()
	if exe != "" {
		exeDir := filepath.Dir(exe)
		if root, ok := findRepoRootFrom(exeDir); ok {
			return root, nil
		}
	}
	return "", errors.New("repo root not found")
}

func findRepoRootFrom(start string) (string, bool) {
	dir := start
	for {
		if _, err := os.Stat(filepath.Join(dir, ".git")); err == nil {
			return dir, true
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			return "", false
		}
		dir = parent
	}
}

func RepoBinaryPath(repoRoot string) string {
	return filepath.Join(repoRoot, "companion", "oc-pocket", "bin", "oc-pocket")
}

func GoModuleDir(repoRoot string) string {
	return filepath.Join(repoRoot, "companion", "oc-pocket")
}
