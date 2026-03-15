import SwiftUI
import ComposeApp

@MainActor
struct WorkspacesSidebarView: View {
    let viewModel: SidebarViewModel
    let onClose: () -> Void
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
            ToolbarItem(placement: .topBarLeading) {
                Button(action: onClose) {
                    Image(systemName: "chevron.left")
                }
            }

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
            if latestUiState?.switchedWorkspaceId != nil {
                return
            }
            onSelectSession()
        }
    }

    @ViewBuilder
    private func sidebarContent(state: SidebarUiState) -> some View {
        ScrollView {
            LazyVStack(spacing: 4) {
                ForEach(state.workspaces, id: \.workspace.projectId) { workspaceWithSessions in
                    let projectId = workspaceWithSessions.workspace.projectId
                    let isExp = expanded.contains(projectId)
                    let isFull = fullyExpanded.contains(projectId)

                    WorkspaceCardView(
                        workspaceWithSessions: workspaceWithSessions,
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
                            let isActiveWorkspace = projectId == state.activeWorkspaceId
                            if isActiveWorkspace {
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
            .padding(.horizontal, 12)
            .padding(.top, 8)
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
