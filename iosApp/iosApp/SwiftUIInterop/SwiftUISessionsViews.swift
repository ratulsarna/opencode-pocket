import Foundation
import SwiftUI
import ComposeApp

@MainActor
struct SwiftUISessionsView: View {
    let kmp: KmpScreenOwnerStore

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let viewModel = kmp.owner.sessionsViewModel()

        Observing(viewModel.uiState) {
            SamFullScreenLoadingView(title: "Loading sessions…")
        } content: { uiState in
            content(uiState: uiState, viewModel: viewModel)
                .navigationTitle("Sessions")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) {
                        Button("Close") { dismiss() }
                    }

                    ToolbarItemGroup(placement: .topBarTrailing) {
                        if uiState.isSearching {
                            ProgressView()
                                .controlSize(.small)
                        }

                        Button {
                            viewModel.createNewSession()
                        } label: {
                            if uiState.isCreatingSession {
                                ProgressView()
                            } else {
                                Image(systemName: "plus")
                            }
                        }
                        .disabled(uiState.isLoading || uiState.isCreatingSession || uiState.isActivating)
                    }
                }
                .searchable(
                    text: Binding(
                        get: { uiState.searchQuery },
                        set: { viewModel.onSearchQueryChanged(query: $0) }
                    ),
                    prompt: "Search sessions"
                )
                .onAppear { viewModel.onScreenVisible() }
                .task(id: uiState.activatedSessionId ?? "nil") {
                    guard uiState.activatedSessionId != nil else { return }
                    viewModel.clearActivation()
                    dismiss()
                }
                .task(id: uiState.newSessionId ?? "nil") {
                    guard uiState.newSessionId != nil else { return }
                    viewModel.clearNewSession()
                    dismiss()
                }
        }
    }

    @ViewBuilder
    private func content(uiState: SessionsUiState, viewModel: SessionsViewModel) -> some View {
        if uiState.isLoading {
            SamFullScreenLoadingView(title: "Loading sessions…")
        } else if uiState.sessions.isEmpty {
            if let error = uiState.error, !error.isEmpty {
                VStack(spacing: 12) {
                    Text(error)
                        .foregroundStyle(.red)
                        .multilineTextAlignment(.center)
                    Button("Retry") { viewModel.refresh() }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                VStack(spacing: 8) {
                    Text(uiState.searchQuery.isEmpty ? "No recent sessions found" : "No results")
                        .foregroundStyle(.secondary)
                    Button("Refresh") { viewModel.refresh() }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        } else {
            List {
                if let error = uiState.error, !error.isEmpty {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(error)
                            .foregroundStyle(.red)
                        Button("Retry") { viewModel.refresh() }
                    }
                }

                if let activationError = uiState.activationError, !activationError.isEmpty {
                    Text(activationError)
                        .foregroundStyle(.red)
                }

                ForEach(uiState.sessions, id: \.id) { session in
                    Button {
                        viewModel.activateSession(sessionId: session.id)
                    } label: {
                        HStack(alignment: .firstTextBaseline, spacing: 12) {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(session.title ?? "Untitled Session")
                                    .font(.headline)
                                    .lineLimit(1)

                                Text(KmpDateFormat.mediumDateTime(session.updatedAt))
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }

                            Spacer(minLength: 0)

                            if uiState.activeSessionId == session.id {
                                Image(systemName: "checkmark.circle.fill")
                                    .foregroundStyle(.green)
                            } else if uiState.activatingSessionId == session.id {
                                ProgressView()
                                    .controlSize(.small)
                            }
                        }
                        .padding(.vertical, 4)
                    }
                    .buttonStyle(.plain)
                    .disabled(uiState.isCreatingSession || uiState.isLoading || uiState.isActivating)
                }
            }
            .listStyle(.plain)
            .refreshable { viewModel.refresh() }
        }
    }
}

@MainActor
struct SwiftUISessionsSheetView: View {
    @StateObject private var kmp = KmpScreenOwnerStore()

    var body: some View {
        NavigationStack {
            SwiftUISessionsView(kmp: kmp)
        }
    }
}
