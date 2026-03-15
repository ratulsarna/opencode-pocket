# Sidebar Navigation Rework — Design Spec

## Problem

Workspaces and sessions are currently separate screens accessed through different paths — the Sessions sheet (modal from chat toolbar) and the Workspaces screen (pushed from Settings). This creates a disjointed experience where the two most closely related concepts (which project am I in, which conversation am I having) are managed in completely different places.

## Solution

Replace both with a unified sidebar that shows workspaces with nested sessions, accessed via a hamburger button on the chat toolbar. Use `NavigationSplitView` so the sidebar automatically adapts between iPhone (full push) and iPad (persistent sidebar) without code changes.

## Design Decisions

| Decision | Choice | Reasoning |
|----------|--------|-----------|
| Sidebar presentation | `NavigationSplitView` (full push on iPhone) | Native iPhone/iPad adaptation for free; forward-compatible |
| Sidebar content | Custom `ScrollView` + `LazyVStack` inside `NavigationSplitView` sidebar column | Avoids `List` styling conflicts with Liquid Glass |
| Workspace tap behavior | Expand/collapse session list | No separate "activate workspace" action; selecting a session implicitly activates its workspace |
| Default sessions shown | Top 3 per workspace (most recent) | Keeps sidebar compact; "View N more" CTA expands |
| "Add Workspace" location | Sidebar toolbar top-right `+` button | Always visible regardless of scroll position |
| "New Session" location | Per-workspace `+` button on each workspace row | Contextual to the workspace |
| Chat toolbar changes | Hamburger left, session title + workspace subtitle center, refresh + gear right | Simplified; sessions button removed; workspace/session context visible at a glance |
| Workspace path display | Leading ellipsis (`…/opencode-pocket`) | Shows the meaningful final folder name within limited toolbar space |
| Glass treatment | Glass on workspace cards; sessions are plain rows inside the card | Avoids cluttered glass-on-glass nesting; clear visual hierarchy |
| State management | New `SidebarViewModel` (Kotlin, app-scoped) absorbs `WorkspacesViewModel` + `SessionsViewModel` | Single source of truth for workspace + session state |

## Navigation Architecture

### Root View (`SwiftUIAppRootView`)

```
SwiftUIAppRootView
├── [NOT PAIRED] NavigationStack
│    └── SwiftUIConnectToOpenCodeView (full-screen, no hamburger, no sidebar)
│
└── [PAIRED] NavigationSplitView(columnVisibility: $sidebarVisibility)
     ├── sidebar:
     │    └── WorkspacesSidebarView (custom ScrollView + LazyVStack)
     │
     └── detail:
          └── NavigationStack
               ├── SwiftUIChatUIKitView (root)
               └── .navigationDestination:
                    ├── .settings → SwiftUISettingsView
                    ├── .connect → SwiftUIConnectToOpenCodeView
                    ├── .modelSelection → SwiftUIModelSelectionView
                    └── .markdownFile → SwiftUIMarkdownFileViewerView
```

### Key Behaviors

- `columnVisibility` is a `@State var sidebarVisibility: NavigationSplitViewVisibility`.
- Hamburger button toggles between `.detailOnly` (hidden) and `.doubleColumn` (shown).
- On iPhone, `.doubleColumn` presents the sidebar as a full-screen push. Tapping a session sets visibility back to `.detailOnly`.
- On iPad (future), the sidebar remains persistent in `.doubleColumn` — no code changes needed.
- The detail column retains its own `NavigationStack` for pushing Settings, Markdown viewer, etc.

### First Run / Unpaired State

- `NavigationSplitView` does not exist. The `[NOT PAIRED]` branch uses a plain `NavigationStack` with `SwiftUIConnectToOpenCodeView` as a full-screen experience.
- No hamburger, no sidebar, no toolbar. Identical to current behavior.
- Once pairing succeeds, app resets (new DI graph), root switches to the `[PAIRED]` branch.

### Paired but Disconnected

- Hamburger is present and functional.
- Sidebar shows last-known workspaces and sessions from cached state.
- Chat screen shows reconnecting banner as today.

## Sidebar Content & Interaction

### Layout

```
┌─────────────────────────────────────┐
│  Workspaces                    [+]  │  ← Sidebar toolbar: title + Add Workspace
├─────────────────────────────────────┤
│                                     │
│  ┌─────────────────────────────────┐│
│  │ 📁 opencode-pocket        [+]  ││  ← Workspace row (+ = new session)
│  ├─────────────────────────────────┤│
│  │   ● Fix auth token refresh     ││  ← Active session (accent dot)
│  │     Add image paste support    ││
│  │     Refactor chat toolbar      ││  ← 3rd session
│  │     View 12 more sessions ▾    ││  ← "See more" CTA
│  └─────────────────────────────────┘│
│                                     │
│  ┌─────────────────────────────────┐│
│  │ 📁 backend-api            [+]  ││  ← Collapsed workspace
│  └─────────────────────────────────┘│
│                                     │
└─────────────────────────────────────┘
```

### Interaction Model

| Action | Behavior |
|--------|----------|
| Tap workspace row | Toggle expand/collapse of that workspace's session list (animated) |
| Tap `[+]` on workspace row | Create new session in that workspace. If different workspace than active, triggers workspace switch (app reset) + new session |
| Tap session row (same workspace) | Switch to that session, dismiss sidebar |
| Tap session row (different workspace) | Trigger workspace switch (app reset), then activate that session |
| Tap "View N more sessions" | Expand to show all sessions for that workspace (animated). CTA changes to "Show less" |
| Tap toolbar `[+]` (Add Workspace) | Present inline text field or sheet for directory path entry |

### Sidebar State

- `expanded: Set<WorkspaceID>` — which workspaces show sessions. **Active workspace expanded by default** when sidebar opens. Others collapsed.
- `fullyExpanded: Set<WorkspaceID>` — which workspaces show all sessions (past initial 3). Default: none.
- Both are SwiftUI `@State` — UI-only, not business logic.

### Session Ordering

Sessions within each workspace are sorted by `updatedAt` descending. The top 3 shown by default are the most recent.

## Chat Toolbar

### Layout

```
[☰]     "Fix auth token refresh" / "…/opencode-pocket"     [↻] [⚙]
```

| Element | Description |
|---------|-------------|
| Left: Hamburger (`line.3.horizontal`) | Toggles sidebar visibility |
| Center line 1: Session title | Active session's title string |
| Center line 2: Workspace path | Last path component with leading ellipsis (`…/opencode-pocket`). If path is short enough, show without ellipsis |
| Right: Refresh button | Existing refresh action |
| Right: Settings gear | Pushes to Settings screen |

### Overlay Elements (Unchanged)

- Indeterminate processing bar (agent working)
- Reconnecting banner
- Error banner with Dismiss / Retry / Revert actions

## Liquid Glass Treatment

### Sidebar

| Element | Treatment |
|---------|-----------|
| Workspace card (collapsed) | `.glassEffect(.regular.interactive(), in: .rect(cornerRadius: 12))` |
| Workspace card (expanded) | Same glass wrapping the entire card including session rows |
| Active workspace card | `.glassEffect(.regular.tint(.accentColor).interactive(), in: .rect(cornerRadius: 12))` |
| Session rows | No individual glass — content within the workspace card's glass surface, separated by subtle spacing |
| "View N more" CTA | No glass — plain text button in accent color |
| Per-workspace `[+]` button | `.buttonStyle(.glass)` |
| Toolbar `[+]` button | `.buttonStyle(.glass)` |

### Design Rationale

Sessions don't get their own glass to avoid glass-on-glass nesting, which looks cluttered. The workspace card is the glass container; sessions are content within it. This creates a clear visual hierarchy: glass cards = workspaces, plain rows inside = sessions.

### Animations

- Sidebar wrapped in `GlassEffectContainer`.
- Each workspace card uses `glassEffectID` with `@Namespace` for smooth glass morphing on expand/collapse.
- Session rows animate with `.transition(.opacity.combined(with: .move(edge: .top)))` inside `withAnimation`.

### Chat Toolbar

- Existing `ChatToolbarGlassView` retains its glass treatment.
- Hamburger button gets `.buttonStyle(.glass)`.

### iOS Version Gating

All `glassEffect` calls gated behind `#available(iOS 26, *)` with fallback to `.background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 12))`.

## Data Flow

### New ViewModel: `SidebarViewModel` (Kotlin, app-scoped)

```kotlin
data class SidebarUiState(
    val workspaces: List<WorkspaceWithSessions>,
    val activeWorkspaceId: String?,
    val activeSessionId: String?,
    val isCreatingSession: Boolean,
    val isCreatingWorkspace: Boolean,
    val isSwitchingWorkspace: Boolean
)

data class WorkspaceWithSessions(
    val workspace: Workspace,
    val sessions: List<Session>,    // sorted by updatedAt desc
    val isLoading: Boolean,         // loading sessions for this workspace
    val error: String?              // per-workspace loading error
)
```

### Session-Workspace Association

Sessions are grouped into workspaces by matching `Session.directory` against `Workspace.worktree`. A session belongs to the workspace whose `worktree` path matches the session's `directory` field.

### Session Fetching Strategy

The current `SessionRepository.getSessions()` API fetches all sessions globally (no workspace/directory filter). The `SidebarViewModel` fetches all sessions once, then groups and filters client-side by matching `Session.directory` to each `Workspace.worktree`. This avoids API changes and is efficient given the expected session count. Lazy loading per-workspace means: the global fetch is triggered on first sidebar open, but the client-side grouping for a collapsed workspace is deferred until expansion.

### Cross-Reset Session Persistence

When `switchWorkspace(workspaceId, sessionId)` is called for a different workspace, the target session ID is written to `AppSettings` via `setCurrentSessionId(sessionId)` **before** triggering the app reset. After reset, the new DI graph reads `getCurrentSessionId()` during initialization and activates that session. This matches the existing pattern where `AppSettings` persists state across resets.

Note: `SidebarUiState.activeSessionId` maps to `AppSettings.currentSessionId` — these refer to the same value, no new persistence key is needed.

### Key Behaviors

- On init, fetches workspaces. Sessions are fetched globally and grouped client-side by matching `Session.directory` to `Workspace.worktree`.
- `createSession(workspaceId)` — creates session, handles workspace switch if needed.
- `addWorkspace(path)` — adds workspace to the list.
- `switchSession(sessionId)` — for same-workspace session switches.
- `switchWorkspace(workspaceId, sessionId)` — writes target session ID to `AppSettings`, then triggers app reset.

### Dropped Feature: Session Search

The current `SessionsView` has `.searchable` for filtering sessions. This is intentionally dropped from the initial sidebar implementation to keep scope focused. Session search can be added later as a filter field within the sidebar if needed.

### Swift Side

- `SidebarViewModel` held in `IosAppViewModelOwner` (app-scoped).
- `WorkspacesSidebarView` observes via `Observing(viewModel.uiState)` SKIE pattern.
- Expand/collapse state is SwiftUI `@State` only.

## Deletions

### Files to Delete

| File | Reason |
|------|--------|
| `iosApp/iosApp/SwiftUIInterop/SwiftUISessionsViews.swift` | Contains both `SwiftUISessionsSheetView` and `SwiftUISessionsView`. Replaced by sidebar |
| `iosApp/iosApp/SwiftUIInterop/SwiftUIWorkspacesViews.swift` | Contains `SwiftUIWorkspacesView`. Replaced by sidebar |
| `composeApp/src/commonMain/kotlin/com/ratulsarna/ocmobile/ui/screen/sessions/SessionsViewModel.kt` | Logic absorbed into `SidebarViewModel` |
| `composeApp/src/commonMain/kotlin/com/ratulsarna/ocmobile/ui/screen/workspaces/WorkspacesViewModel.kt` | Logic absorbed into `SidebarViewModel` |
| `composeApp/src/jvmTest/kotlin/com/ratulsarna/ocmobile/ui/screen/sessions/SessionsViewModelTest.kt` | Tests for deleted `SessionsViewModel` — migrate relevant cases to `SidebarViewModelTest` |

### Settings Cleanup (`SwiftUISettingsView`)

Remove:
- "Workspace" row
- "Sessions" row

Keep:
- Status pills (Connected/Disconnected, Context usage)
- Connect to OpenCode row
- Model selection row
- Agent selection row/sheet
- Theme picker
- Advanced section

### Navigation Routes (`SwiftUIAppRoute`)

Remove:
- `.workspaces`

Keep:
- `.connect`
- `.settings`
- `.modelSelection`
- `.markdownFile(path:openId:)`

### DI Cleanup

- Remove `SessionsViewModel` from `IosScreenViewModelOwner`.
- Remove `WorkspacesViewModel` from `IosAppViewModelOwner`.
- Add `SidebarViewModel` to `IosAppViewModelOwner` (app-scoped).
- `IosScreenViewModelOwner` retains only `markdownFileViewerViewModel()` after this change.

## New Files

| File | Location | Purpose |
|------|----------|---------|
| `WorkspacesSidebarView.swift` | `iosApp/iosApp/SwiftUIInterop/` | Sidebar root view with toolbar and workspace list |
| `WorkspaceCardView.swift` | `iosApp/iosApp/SwiftUIInterop/` | Individual workspace card with expand/collapse, session rows, glass treatment |
| `SidebarViewModel.kt` | `composeApp/src/commonMain/kotlin/com/ratulsarna/ocmobile/ui/screen/sidebar/` | Combined workspace + session state management |
