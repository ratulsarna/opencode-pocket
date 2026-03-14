# Architecture

Architectural decisions, implementation boundaries, and important patterns for this mission.

**What belongs here:** shell/body ownership, state sources, bridging rules, mission-specific architectural decisions.
**What does NOT belong here:** step-by-step validation instructions (use `user-testing.md`).

---

- Mission architecture is a **hybrid SwiftUI shell + UIKit transcript body**.
- Keep `ChatViewModel` / `ChatUiState` as the primary state source.
- The top area and composer should move toward Remodex-inspired structure and styling, but the transcript stays UIKit-backed for scroll/callback stability.
- Prefer extracting small presentation/state-mapping seams for tests rather than rewriting shared chat logic.
- New validation-only hooks should be narrow mock/debug fixtures instead of broad production behavior changes.
