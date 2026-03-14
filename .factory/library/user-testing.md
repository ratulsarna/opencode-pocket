# User Testing

Validation surface findings, runtime constraints, and concurrency guidance.

**What belongs here:** how validators should run the app, what evidence to capture, which scenarios need fixtures, and concurrency/resource guidance.
**What does NOT belong here:** implementation details that are not needed for validation.

---

## Validation Surface

- Primary surface: iOS Simulator running the `iosApp` scheme.
- Primary mode: `OC_POCKET_MOCK_MODE=1`.
- Preferred tooling: XcodeBuildMCP for simulator build/run/test flows.
- Baseline automated checks before/after UI validation:
  - `./gradlew --max-workers=8 :composeApp:jvmTest`
  - `./gradlew --max-workers=8 :composeApp:compileKotlinIosSimulatorArm64`
  - simulator build of `iosApp`
- Required evidence types in this mission:
  - screenshots of top area and composer states
  - screen recordings for scrolling, keyboard, refresh, and send/stop transitions
  - UI hierarchy captures for shell/composer layout when static screenshots are insufficient

## Validation Notes

- Default mock mode is enough for happy-path shell/transcript flows.
- Add deterministic fixtures or injected-state hooks for:
  - reconnecting/error/stacked top-area states
  - Thinking-capable model variants
  - failing-send recovery
  - pinned/unpinned shell-height-change scenarios that are hard to trigger manually
- When capturing evidence, use obviously different mock content per session so stale-content regressions are detectable in recordings.

## Validation Concurrency

- Machine profile from dry run: 16 CPU cores, ~48 GB RAM.
- Safe validator concurrency for this mission: **2**.
- Rationale: Gradle and simulator build steps are both safe on memory, but overlapping the heaviest iOS build/run work is unnecessary and can add CPU contention. Prefer serial simulator-heavy validation and at most one parallel lightweight validator.
