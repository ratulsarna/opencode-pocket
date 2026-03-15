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
    val isLoading: Boolean          // loading sessions for this workspace
)
```

### Key Behaviors

- On init, fetches workspaces. Sessions for the **active workspace** are fetched eagerly. Sessions for other workspaces are fetched **lazily** — only when that workspace is first expanded.
- `createSession(workspaceId)` — creates session, handles workspace switch if needed.
- `addWorkspace(path)` — adds workspace to the list.
- `switchSession(sessionId)` — for same-workspace session switches.
- `switchWorkspace(workspaceId, sessionId)` — triggers app reset + session activation.

### Swift Side

- `SidebarViewModel` held in `IosAppViewModelOwner` (app-scoped).
- `WorkspacesSidebarView` observes via `Observing(viewModel.uiState)` SKIE pattern.
- Expand/collapse state is SwiftUI `@State` only.

## Deletions

### Files to Delete

| File | Reason |
|------|--------|
| `SwiftUISessionsSheetView.swift` | Replaced by sidebar |
| `SwiftUISessionsView.swift` | Replaced by sidebar |
| `SwiftUIWorkspacesView.swift` | Replaced by sidebar |
| `SessionsViewModel.kt` | Logic absorbed into `SidebarViewModel` |
| `WorkspacesViewModel.kt` | Logic absorbed into `SidebarViewModel` |

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

- Remove `SessionsViewModel` and `WorkspacesViewModel` from `IosAppViewModelOwner` / `IosScreenViewModelOwner`.
- Add `SidebarViewModel` to `IosAppViewModelOwner` (app-scoped).

## New Files

| File | Location | Purpose |
|------|----------|---------|
| `WorkspacesSidebarView.swift` | `iosApp/iosApp/SwiftUIInterop/` | Sidebar root view with toolbar and workspace list |
| `WorkspaceCardView.swift` | `iosApp/iosApp/SwiftUIInterop/` | Individual workspace card with expand/collapse, session rows, glass treatment |
| `SidebarViewModel.kt` | `composeApp/src/commonMain/kotlin/ui/screen/` | Combined workspace + session state management |
