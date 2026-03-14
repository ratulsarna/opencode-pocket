---
name: ios-chat-transcript-worker
description: Preserve and re-host the UIKit chat transcript inside the new SwiftUI shell while keeping scroll, keyboard, and session behavior correct.
---

# iOS Chat Transcript Worker

NOTE: Startup, baseline setup, and cleanup are handled by `worker-base`. This skill defines the work procedure for transcript/body integration features.

## When to Use This Skill

Use this skill for features that primarily change:

- UIKit transcript hosting inside the SwiftUI shell
- pinned/unpinned scroll behavior
- scroll-to-bottom affordance behavior
- keyboard avoidance and shell-height-change handling
- transcript actions, linked destinations, and session-switch correctness
- attachment error placement near the composer

Do not use this skill for top chrome restyling or main composer-control layout; those belong to `ios-chat-shell-worker`.

## Work Procedure

1. Read `mission.md`, `validation-contract.md`, mission `AGENTS.md`, and the relevant `.factory/library/*.md` files before editing.
2. Inspect the current transcript ownership in `ChatViewController.swift`, `ChatAutoScrollPolicy.swift`, `ChatMessageCell.swift`, and any host/interop layer touched by the feature.
3. Write a failing test first for any extracted logic or changed scroll/state behavior:
   - extend `ChatAutoScrollPolicyTests` when scroll decisions change
   - add focused XCTest coverage for any new host bridge or session-state mapping helper
4. Preserve the approved boundary: UIKit keeps transcript/body responsibilities; SwiftUI owns shell chrome. Reduce monolithic controller ownership carefully instead of rewriting the transcript in SwiftUI.
5. Implement the feature in small verifiable slices:
   - body host and inset/height bridge
   - pinned behavior
   - unpinned recovery
   - session-switch stale-content clearing
   - transcript actions / linked content / attachment error placement
6. Keep `ChatViewModel` / `ChatUiState` behavior intact unless the feature explicitly requires a narrow supporting change.
7. If validation requires hard-to-trigger states (failing send, stale shell, special session fixtures), add the smallest deterministic mock/debug hook needed for simulator evidence.
8. Run the relevant validators before handoff:
   - `./gradlew --max-workers=8 :composeApp:jvmTest` when shared or scroll logic changes
   - `./gradlew --max-workers=8 :composeApp:compileKotlinIosSimulatorArm64`
   - simulator build of `iosApp`
9. Manually verify the exact transcript behaviors tied to the feature’s `fulfills` IDs in iOS Simulator mock mode, using XcodeBuildMCP when practical.
10. In the handoff, report the validation IDs covered, scroll/keyboard scenarios exercised, and any shell-side dependency still needed.

## Example Handoff

```json
{
  "salientSummary": "Re-hosted the UIKit transcript inside the SwiftUI shell and preserved pinned/unpinned scroll behavior, keyboard handling, and session-switch stale-content clearing. Verified the scroll-to-bottom affordance and latest-message landing behavior in simulator mock mode.",
  "whatWasImplemented": "Updated the transcript host boundary so the UIKit timeline now receives shell inset changes from SwiftUI while preserving ChatAutoScrollPolicy behavior. Kept latest-message landing on chat entry, maintained separate pinned and unpinned keyboard paths, preserved the scroll-to-bottom affordance, and cleared stale session-scoped transcript state during session switches.",
  "whatWasLeftUndone": "Injected reconnect/error shell-state fixtures still need the shell worker’s top-area presentation to fully validate cross-area error coherence.",
  "verification": {
    "commandsRun": [
      {
        "command": "./gradlew --max-workers=8 :composeApp:jvmTest",
        "exitCode": 0,
        "observation": "Shared tests passed after updating the scroll policy integration helper."
      },
      {
        "command": "./gradlew --max-workers=8 :composeApp:compileKotlinIosSimulatorArm64",
        "exitCode": 0,
        "observation": "Shared iOS compile passed."
      },
      {
        "command": "xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -destination 'generic/platform=iOS Simulator' build",
        "exitCode": 0,
        "observation": "Simulator build succeeded for transcript integration changes."
      }
    ],
    "interactiveChecks": [
      {
        "action": "Open a populated mock chat, scroll up, receive new content, and inspect the scroll-to-bottom affordance",
        "observed": "Unpinned users were not yanked to the bottom, the affordance appeared above the composer, and tapping it returned to the newest content."
      },
      {
        "action": "Focus the composer while pinned and then while unpinned",
        "observed": "Pinned users stayed aligned to the newest content as the keyboard changed; unpinned users kept their place and retained the affordance."
      }
    ]
  },
  "tests": {
    "added": [
      {
        "file": "iosApp/iosAppTests/ChatAutoScrollPolicyTests.swift",
        "cases": [
          {
            "name": "tail_row_growth_keeps_pinned_user_aligned",
            "verifies": "Pinned users stay at the newest content when visible tail content grows."
          },
          {
            "name": "keyboard_change_does_not_repin_unpinned_user",
            "verifies": "Unpinned users keep their place during keyboard frame changes."
          }
        ]
      }
    ]
  },
  "discoveredIssues": [
    {
      "severity": "medium",
      "description": "Thinking-capable mock fixtures are still required to validate main composer-control parity in simulator mock mode.",
      "suggestedFix": "Track under the shell/composer input feature or add a dedicated lightweight mock fixture."
    }
  ]
}
```

## When to Return to Orchestrator

- The feature would require moving transcript rendering wholesale to SwiftUI rather than preserving the approved UIKit boundary.
- Required shell inset or composer-height signals do not exist and cannot be added safely within the current feature.
- Session-switch, failing-send, or stale-shell validation requires a broader fixture system than the feature reasonably owns.
- The feature is blocked by unresolved shell/composer API changes from another pending feature.
