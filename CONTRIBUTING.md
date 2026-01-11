# Contributing

Thanks for helping improve **OpenCode Pocket**.

## Quickstart (dev)

### Prerequisites

- macOS
- Xcode (for iOS builds)
- JDK (for Gradle/Kotlin)
- Go (for `oc-pocket`)

### Build & test

From repo root:

- Kotlin unit tests: `./gradlew :composeApp:jvmTest`
- Kotlin iOS compile: `./gradlew :composeApp:compileKotlinIosSimulatorArm64`
- iOS build (simulator): `xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -destination 'generic/platform=iOS Simulator' build`
- Companion CLI tests: `cd companion/oc-pocket && go test ./...`

### Run

- iOS app: open `iosApp/iosApp.xcodeproj` and run scheme `iosApp`.
- Companion CLI: `cd companion/oc-pocket && go run . setup`

Note: running `oc-pocket setup` requires a working `opencode` binary on your PATH.

## Releasing `oc-pocket`

This repo publishes `oc-pocket` binaries via GitHub Actions on tags:

- Create a tag like `oc-pocket-v0.1.0`
- Push the tag to GitHub

Example:

```bash
git tag oc-pocket-v0.1.0
git push origin oc-pocket-v0.1.0
```

## Project structure

- `composeApp/`: Kotlin Multiplatform shared code (networking, domain models, repositories, shared ViewModels)
- `iosApp/`: native iOS app and extensions
- `companion/`: macOS companion CLI (`oc-pocket`)

## Pull requests

- Keep PRs focused.
- Include a short description + testing notes (what you ran).
- For UI changes, include screenshots/screen recordings when possible.

## Security

Do not include secrets (tokens, pairing payloads, private URLs) in issues, PRs, or commits.
See `SECURITY.md` for reporting vulnerabilities.
