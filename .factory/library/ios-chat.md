# iOS Chat Surface

Mission-specific notes for the Pocket chat screen.

**What belongs here:** file ownership, current chat entrypoints, and known risky integration seams.
**What does NOT belong here:** general environment notes (use `environment.md`).

---

- Current chat entrypoint: `iosApp/iosApp/ChatUIKit/SwiftUIChatUIKitView.swift` inside `SwiftUIAppRootView`.
- Current risk boundary: `ChatViewController` owns transcript, header banners, composer container, keyboard coupling, attachment error UI, and scroll-to-bottom behavior in one layout tree.
- Key risky seams for the mission:
  - top-area ownership split between SwiftUI and UIKit
  - composer state parity (Thinking, slash commands, mentions, send/stop)
  - pinned/unpinned transcript behavior during shell height changes
  - session-switch stale-content clearing
- Reuse existing bridging utilities where possible instead of creating parallel state pipes.
