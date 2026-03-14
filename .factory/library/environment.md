# Environment

Environment variables, external dependencies, and setup notes.

**What belongs here:** required env vars, external services, simulator/debug-mode notes, validation prerequisites.
**What does NOT belong here:** service ports/commands (use `.factory/services.yaml`).

---

- Primary validation mode for this mission is `OC_POCKET_MOCK_MODE=1`.
- Mock mode is sufficient for the required chat-shell refactor; live OpenCode pairing is out of scope unless the orchestrator changes scope.
- Several contract items need deterministic fixture or injected-state support in mock mode:
  - reconnect/error/stacked top-area states
  - Thinking-capable versus Thinking-unavailable model states
  - failing-send recovery states
- Preserve the pre-existing uncommitted change in `iosApp/iosApp.xcodeproj/project.pbxproj` unless mission work must legitimately extend the Xcode project.
