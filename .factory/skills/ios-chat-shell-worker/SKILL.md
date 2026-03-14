---
name: ios-chat-shell-worker
description: Implement the SwiftUI-owned chat shell, top chrome, and Remodex-inspired composer while preserving existing chat behavior.
---

# iOS Chat Shell Worker

NOTE: Startup, baseline setup, and cleanup are handled by `worker-base`. This skill defines the work procedure for shell/composer features.

## When to Use This Skill

Use this skill for features that primarily change:

- the chat top area / title / trailing actions
- reconnecting, error, and processing shell surfaces
- the composer shell and its visible controls
- Thinking placement and main-control behavior
- slash/mention input affordances that live with the composer shell

Do not use this skill for transcript-hosting, scroll-to-bottom, keyboard-pinning, or session-switch transcript correctness; those belong to `ios-chat-transcript-worker`.

## Work Procedure

1. Read `mission.md`, `validation-contract.md`, mission `AGENTS.md`, and the relevant `.factory/library/*.md` files before touching code.
2. Inspect the current SwiftUI/UIKit split in `SwiftUIChatUIKitView.swift`, `ChatViewController.swift`, `ChatComposerView.swift`, and any existing SwiftUI interop helpers.
3. Before implementation, identify the smallest testable seam for the feature:
   - if you extract state-mapping or visibility logic, write a failing XCTest/JVM test first
   - if the change is mostly layout, add the smallest deterministic test around any new helper you introduce, then proceed with UI code
4. Implement shell/composer changes in small steps:
   - establish the top SwiftUI region first
   - keep refresh/sessions/settings wired and visible
   - port or restyle the composer card without losing attachment/send/stop behavior
   - move Thinking out of the `+` menu and into the main controls
5. Reuse `ChatViewModel` / `ChatUiState` as the source of truth. Do not fork or duplicate chat state unless the orchestrator explicitly asks.
6. If a validation-only scenario is needed (Thinking-capable model, error fixture, failing send), add the narrowest mock/debug fixture possible instead of broad production changes.
7. Run the relevant validators before handoff:
   - `./gradlew --max-workers=8 :composeApp:jvmTest` when shared/state logic changes
   - `./gradlew --max-workers=8 :composeApp:compileKotlinIosSimulatorArm64`
   - simulator build of `iosApp`
8. Manually verify the feature in iOS Simulator mock mode. Prefer XcodeBuildMCP for build/run/test flows and capture the specific evidence tied to the feature’s `fulfills` IDs.
9. In the handoff, name the exact validation IDs covered, the files changed, the commands run, and any remaining transcript-side dependencies.

## Example Handoff

```json
{
  "salientSummary": "Moved the chat top chrome into a single SwiftUI shell region and rebuilt the composer card so Thinking now lives in the main controls instead of the + menu. Verified the refreshed shell in simulator mock mode and preserved refresh/sessions/settings actions.",
  "whatWasImplemented": "Updated SwiftUIChatUIKitView and related shell components so the top area is a single translucent region with a one-line OpenCode title and persistent top-right actions. Reworked the composer presentation into a single rounded card, kept Photos/Files/Paste in the + menu, exposed Thinking in the main composer controls, preserved send/stop behavior, and kept the runtime/access row removed.",
  "whatWasLeftUndone": "Transcript scroll-to-bottom behavior during shell height changes still depends on the timeline integration feature and was not modified here.",
  "verification": {
    "commandsRun": [
      {
        "command": "./gradlew --max-workers=8 :composeApp:jvmTest",
        "exitCode": 0,
        "observation": "Shared tests passed after extracting the composer visibility helper."
      },
      {
        "command": "./gradlew --max-workers=8 :composeApp:compileKotlinIosSimulatorArm64",
        "exitCode": 0,
        "observation": "Shared iOS compile passed."
      },
      {
        "command": "xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -destination 'generic/platform=iOS Simulator' build",
        "exitCode": 0,
        "observation": "Simulator build succeeded for the shell/composer changes."
      }
    ],
    "interactiveChecks": [
      {
        "action": "Launch simulator app in OC_POCKET_MOCK_MODE=1 and inspect idle chat shell",
        "observed": "Top area rendered as one translucent region with one-line OpenCode title plus visible refresh/sessions/settings controls."
      },
      {
        "action": "Open the + menu and the Thinking control from the composer",
        "observed": "+ menu contained only Photos, Files, and Paste; Thinking opened from the main composer controls and was not nested under +."
      }
    ]
  },
  "tests": {
    "added": [
      {
        "file": "iosApp/iosAppTests/ChatComposerShellStateTests.swift",
        "cases": [
          {
            "name": "thinking_control_visibility_follows_available_variants",
            "verifies": "Thinking only appears when the active model exposes variants."
          }
        ]
      }
    ]
  },
  "discoveredIssues": [
    {
      "severity": "medium",
      "description": "Pinned transcript alignment still needs a body-host bridge when the top area changes height.",
      "suggestedFix": "Handle in the timeline integration feature by exposing shell inset changes to the UIKit transcript host."
    }
  ]
}
```

## When to Return to Orchestrator

- The feature requires transcript-owned hooks or scroll metrics that do not exist yet.
- The change would force a full UIKit transcript rewrite rather than the approved hybrid boundary.
- The only practical way to validate a required assertion is a new fixture or mock hook that would materially expand scope.
- The pre-existing `project.pbxproj` modification conflicts with necessary mission work and cannot be safely worked around.
