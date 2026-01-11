# Security Policy

## Reporting a vulnerability

If you believe you have found a security vulnerability, please **do not** open a public issue.

Preferred: report via **GitHub Security Advisories** for this repository.

If you can’t use Security Advisories, open an issue with minimal detail and clearly mark it as **SECURITY**; a maintainer will follow up to move the discussion to a private channel.

## Supported versions

This project is currently in active development. Security fixes will be made on `main` and released as-needed.

## Current security posture (pre-1.0)

This project is currently a “source-only” OSS baseline and is still hardening.

- **iOS token storage:** stored locally in app settings (not Keychain yet).
- **macOS token storage:** stored locally in the `oc-pocket` config directory with user-only file permissions (not Keychain yet).

### Planned hardening

- Move token storage to **Keychain** on both iOS and macOS before **Target B (OSS + Releases)**.
