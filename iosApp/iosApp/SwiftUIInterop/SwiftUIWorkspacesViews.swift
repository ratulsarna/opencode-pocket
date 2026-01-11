import SwiftUI
import ComposeApp

@MainActor
struct SwiftUIWorkspacesView: View {
    let viewModel: WorkspacesViewModel
    let onDidSwitchWorkspace: () -> Void

    @Environment(\.dismiss) private var dismiss

    @State private var isShowingAddWorkspace = false
    @State private var draftDirectory: String = ""
    @State private var didAttemptAdd = false

    var body: some View {
        Observing(viewModel.uiState) {
            SamFullScreenLoadingView(title: "Loading workspaces…")
        } content: { uiState in
            List {
                if let activationError = uiState.activationError, !activationError.isEmpty {
                    Text(activationError)
                        .foregroundStyle(.red)
                }

                if let error = uiState.error, !error.isEmpty {
                    Text(error)
                        .foregroundStyle(.red)
                }

                ForEach(uiState.workspaces, id: \.projectId) { workspace in
                    Button {
                        if uiState.activeProjectId == workspace.projectId { return }
                        viewModel.activateWorkspace(projectId: workspace.projectId)
                    } label: {
                        HStack(alignment: .firstTextBaseline, spacing: 12) {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(workspaceTitle(workspace))
                                    .font(.headline)
                                    .lineLimit(1)

                                Text(workspace.worktree)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(1)
                            }

                            Spacer(minLength: 0)

                            if uiState.activeProjectId == workspace.projectId {
                                Image(systemName: "checkmark.circle.fill")
                                    .foregroundStyle(.green)
                            } else if uiState.activatingProjectId == workspace.projectId {
                                ProgressView()
                                    .controlSize(.small)
                            }
                        }
                        .padding(.vertical, 4)
                    }
                    .buttonStyle(.plain)
                    .disabled(uiState.isSaving || uiState.isActivating)
                }
            }
            .navigationTitle("Workspaces")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        isShowingAddWorkspace = true
                    } label: {
                        Image(systemName: "plus")
                    }
                    .disabled(uiState.isSaving || uiState.isActivating)
                }
            }
            .sheet(isPresented: $isShowingAddWorkspace) {
                NavigationStack {
                    Form {
                        Section("Directory") {
                            TextField("/path/to/project", text: $draftDirectory)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                        }

                        Section {
                            Text("Enter a directory path on the server machine. We'll resolve it to the project root (worktree).")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }

                        if let error = uiState.error, !error.isEmpty {
                            Section {
                                Text(error)
                                    .foregroundStyle(.red)
                            }
                        }
                    }
                    .navigationTitle("Add Workspace")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .topBarLeading) {
                            Button("Cancel") {
                                isShowingAddWorkspace = false
                                didAttemptAdd = false
                            }
                        }

                        ToolbarItem(placement: .topBarTrailing) {
                            Button("Save") {
                                didAttemptAdd = true
                                viewModel.addWorkspace(directoryInput: draftDirectory)
                            }
                            .disabled(uiState.isSaving || draftDirectory.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        }
                    }
                }
            }
            .refreshable {
                viewModel.refresh()
            }
            .task {
                viewModel.refresh()
            }
            .task(id: uiState.isSaving) {
                guard isShowingAddWorkspace else { return }
                guard didAttemptAdd else { return }
                guard uiState.isSaving == false else { return }
                guard (uiState.error ?? "").isEmpty else { return }

                didAttemptAdd = false
                draftDirectory = ""
                isShowingAddWorkspace = false
            }
            .task(id: uiState.activatedProjectId ?? "nil") {
                guard uiState.activatedProjectId != nil else { return }
                viewModel.clearActivation()
                onDidSwitchWorkspace()
                dismiss()
            }
        }
    }

    private func workspaceTitle(_ workspace: Workspace) -> String {
        if let name = workspace.name?.trimmingCharacters(in: .whitespacesAndNewlines), !name.isEmpty {
            return name
        }

        let path = workspace.worktree.trimmingCharacters(in: .whitespacesAndNewlines)
        if path.isEmpty {
            return workspace.projectId
        }

        let component = URL(fileURLWithPath: path).lastPathComponent
        return component.isEmpty ? path : component
    }
}

