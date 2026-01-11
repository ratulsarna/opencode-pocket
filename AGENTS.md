# Repository Guidelines

## Project Structure & Module Organization

- **Reference repo (read-only):** OpenCode lives at `<PATH_TO_OPENCODE_REPO>` (do not modify). This project is building an **iOS app equivalent of OpenCode’s frontend TUI**, so use the TUI sources under `packages/opencode/src/cli/cmd/tui/` as the main UX/feature reference.

- `composeApp/`: Kotlin Multiplatform shared code (networking, domain models, repositories, shared ViewModels). iOS ships this as the static framework `ComposeApp` (SKIE enabled).
  - `composeApp/src/commonMain/...`: cross-platform Kotlin source
  - `composeApp/src/iosMain/...`: iOS bridges (Swift/Kotlin interop, share-extension hooks)
  - `composeApp/src/jvmTest/...`: JVM unit tests for shared logic
- `iosApp/`: native iOS app and extensions
  - `iosApp/iosApp/`: SwiftUI app + some UIKit chat components
  - `iosApp/OCMobileShareExtension/`: Share Extension
  - `iosApp/iosAppTests/`: XCTest tests (run from Xcode)
- `companion/`: macOS companion CLI (`oc-pocket`)

Notes:
- Local planning notes live in gitignored folders (e.g. `docs/ai/`, `.ai/plans/`).

## Build, Test, and Development Commands

- iOS (Xcode): `open iosApp/iosApp.xcodeproj` then build/run the `iosApp` scheme.
- Kotlin (iOS compile): `./gradlew :composeApp:compileKotlinIosSimulatorArm64`
- Kotlin framework (simulator): `./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64`
- Shared Kotlin unit tests (JVM): `./gradlew :composeApp:jvmTest`

## Coding Style & Naming Conventions

- Kotlin: 4-space indentation; `PascalCase` for types, `camelCase` for functions/properties, `UPPER_SNAKE_CASE` for constants.
- Swift: follow existing SwiftUI/UIKit patterns in `iosApp/iosApp/`; keep diffs small and consistent (no formatter is enforced in-repo).
- Prefer explicit names over abbreviations; avoid cross-layer leakage (UI ↔ data).

## Testing Guidelines

- Kotlin tests: place in `composeApp/src/jvmTest/kotlin/**`, name files `*Test.kt`.
- iOS tests: place in `iosApp/iosAppTests`, use XCTest conventions (`*Tests.swift`), run via Xcode test actions.

## Commit & Pull Request Guidelines

- Git history may be empty on some branches; use a consistent convention going forward (recommended: Conventional Commits like `feat: …`, `fix: …`, `chore: …`).
- PRs: include a short description, testing notes (commands run), and screenshots/screen recordings for UI changes. Link the issue/ticket when applicable.

## Configuration & Security Tips

- API endpoints are currently hard-coded in `composeApp/src/commonMain/kotlin/com/ratulsarna/ocmobile/data/api/ApiConfig.kt`; update for your environment and avoid committing secrets.
*** End Patch
