import SwiftUI
import ComposeApp

@MainActor
struct WorkspacesSidebarView: View {
    let viewModel: SidebarViewModel
    let onSelectSession: () -> Void
    let onRequestAppReset: () -> Void

    @StateObject private var uiStateEvents = KmpUiEventBridge<SidebarUiState>()
    @State private var latestUiState: SidebarUiState?
    @State private var expanded: Set<String> = []
    @State private var fullyExpanded: Set<String> = []
    @State private var isShowingAddWorkspace = false
    @State private var draftDirectory = ""

    var body: some View {
        Group {
            if let state = latestUiState {
                sidebarContent(state: state)
            } else {
                ProgressView()
            }
        }
        .navigationTitle("Workspaces")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                if let state = latestUiState, state.isCreatingWorkspace {
                    ProgressView()
                        .controlSize(.small)
                } else {
                    Button(action: { isShowingAddWorkspace = true }) {
                        Image(systemName: "plus")
                    }
                }
            }
        }
        .sheet(isPresented: $isShowingAddWorkspace) {
            addWorkspaceSheet
        }
        .onAppear {
            uiStateEvents.start(flow: viewModel.uiState) { state in
                latestUiState = state

                // Auto-expand active workspace on first load
                if expanded.isEmpty, let activeId = state.activeWorkspaceId {
                    expanded.insert(activeId)
                    viewModel.loadSessionsForWorkspace(projectId: activeId)
                }
            }
        }
        .onDisappear {
            uiStateEvents.stop()
        }
        .task(id: latestUiState?.switchedWorkspaceId ?? "") {
            guard let switchedId = latestUiState?.switchedWorkspaceId, !switchedId.isEmpty else { return }
            viewModel.clearWorkspaceSwitch()
            onRequestAppReset()
        }
        .task(id: latestUiState?.createdSessionId ?? "") {
            guard let sessionId = latestUiState?.createdSessionId, !sessionId.isEmpty else { return }
            viewModel.clearCreatedSession()
            // If a workspace switch is also pending, let that handle the reset
            if latestUiState?.switchedWorkspaceId != nil {
                return
            }
            onSelectSession()
        }
    }

    @ViewBuilder
    private func sidebarContent(state: SidebarUiState) -> some View {
        ScrollView {
            glassContainerWrapper {
                LazyVStack(spacing: 12) {
                    ForEach(state.workspaces, id: \.workspace.projectId) { workspaceWithSessions in
                        let projectId = workspaceWithSessions.workspace.projectId
                        let isActive = projectId == state.activeWorkspaceId
                        let isExp = expanded.contains(projectId)
                        let isFull = fullyExpanded.contains(projectId)

                        WorkspaceCardView(
                            workspaceWithSessions: workspaceWithSessions,
                            isActive: isActive,
                            activeSessionId: state.activeSessionId,
                            isExpanded: isExp,
                            isFullyExpanded: isFull,
                            isCreatingSession: state.isCreatingSession,
                            onToggleExpand: {
                                withAnimation(.easeInOut(duration: 0.25)) {
                                    if expanded.contains(projectId) {
                                        expanded.remove(projectId)
                                    } else {
                                        expanded.insert(projectId)
                                        // Load sessions on first expand (or background refresh if cached)
                                        viewModel.loadSessionsForWorkspace(projectId: projectId)
                                    }
                                }
                            },
                            onToggleFullExpand: {
                                withAnimation(.easeInOut(duration: 0.25)) {
                                    if fullyExpanded.contains(projectId) {
                                        fullyExpanded.remove(projectId)
                                    } else {
                                        fullyExpanded.insert(projectId)
                                    }
                                }
                            },
                            onSelectSession: { sessionId in
                                if isActive {
                                    viewModel.switchSession(sessionId: sessionId)
                                    onSelectSession()
                                } else {
                                    viewModel.switchWorkspace(projectId: projectId, sessionId: sessionId)
                                }
                            },
                            onCreateSession: {
                                viewModel.createSession(workspaceProjectId: projectId)
                            }
                        )
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
            }
        }
    }

    @ViewBuilder
    private func glassContainerWrapper<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        if #available(iOS 26, *) {
            GlassEffectContainer(spacing: 12) {
                content()
            }
        } else {
            content()
        }
    }

    @ViewBuilder
    private var addWorkspaceSheet: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Directory path", text: $draftDirectory)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                } footer: {
                    Text("Enter the full directory path on the server machine.")
                }
            }
            .navigationTitle("Add Workspace")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        draftDirectory = ""
                        isShowingAddWorkspace = false
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        let trimmed = draftDirectory.trimmingCharacters(in: .whitespacesAndNewlines)
                        guard !trimmed.isEmpty else { return }
                        viewModel.addWorkspace(directoryInput: trimmed)
                        draftDirectory = ""
                        isShowingAddWorkspace = false
                    }
                    .disabled(draftDirectory.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }
}
