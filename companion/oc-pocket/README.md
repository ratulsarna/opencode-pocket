# oc-pocket (macOS companion CLI)

This is a macOS companion CLI for `opencode-pocket` that provides:

- A stable local gateway endpoint (default port `4096`)
- An auth token + pairing payload (QR + copy/paste)
- A per-user `launchd` agent to keep the backend running

## Local dev

From repo root:

- `cd companion/oc-pocket && go test ./...`
- `cd companion/oc-pocket && go run . --help`

## Setup (dev-first)

This project currently uses a “dev-first” install model: `setup` builds `companion/oc-pocket/bin/oc-pocket` inside the repo and installs a per-user LaunchAgent that runs `oc-pocket agent`.

From repo root:

- `cd companion/oc-pocket`
- `go run . setup`

Advanced:

- `go run . setup --mode lan`
- `go run . setup --mode tailscale`
- `go run . setup --mode localhost`
- `go run . setup --skip-launchd --config-dir /tmp/oc-pocket-test --opencode-path /usr/bin/true` (smoke test only; writes plist into the config dir, not `~/Library/LaunchAgents/`)
- `go run . uninstall` (removes the LaunchAgent)
- `go run . uninstall --purge` (also removes the config dir)
