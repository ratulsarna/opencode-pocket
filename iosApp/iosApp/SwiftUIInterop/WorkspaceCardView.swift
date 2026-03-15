import SwiftUI
import ComposeApp

@MainActor
struct WorkspaceCardView: View {
    let workspaceWithSessions: WorkspaceWithSessions
    let isActive: Bool
    let activeSessionId: String?
    let isExpanded: Bool
    let isFullyExpanded: Bool
    let isCreatingSession: Bool
    let onToggleExpand: () -> Void
    let onToggleFullExpand: () -> Void
    let onSelectSession: (String) -> Void
    let onCreateSession: () -> Void

    private var displayTitle: String {
        if let name = workspaceWithSessions.workspace.name, !name.isEmpty {
            return name
        }
        let worktree = workspaceWithSessions.workspace.worktree
        return (worktree as NSString).lastPathComponent.isEmpty
            ? workspaceWithSessions.workspace.projectId
            : (worktree as NSString).lastPathComponent
    }

    private var sessions: [Session] {
        let all = workspaceWithSessions.sessions
        if isFullyExpanded { return Array(all) }
        return Array(all.prefix(3))
    }

    private var hiddenCount: Int {
        max(0, Int(workspaceWithSessions.sessions.count) - 3)
    }

    @Namespace private var glassNamespace

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Header row
            HStack(spacing: 10) {
                Image(systemName: "folder.fill")
                    .foregroundStyle(.secondary)
                    .font(.body)

                Text(displayTitle)
                    .font(.system(.subheadline, design: .rounded).weight(.semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(1)

                Spacer(minLength: 0)

                if isCreatingSession {
                    ProgressView()
                        .controlSize(.small)
                } else {
                    if #available(iOS 26, *) {
                        Button(action: onCreateSession) {
                            Image(systemName: "plus")
                                .font(.system(.caption, weight: .semibold))
                        }
                        .buttonStyle(.glass)
                    } else {
                        Button(action: onCreateSession) {
                            Image(systemName: "plus")
                                .font(.system(.caption, weight: .semibold))
                                .foregroundStyle(.secondary)
                        }
                        .buttonStyle(.plain)
                    }
                }

                Image(systemName: isExpanded ? "chevron.up" : "chevron.down")
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .contentShape(Rectangle())
            .onTapGesture(perform: onToggleExpand)

            // Expanded session list
            if isExpanded {
                if workspaceWithSessions.isLoading {
                    HStack {
                        Spacer()
                        ProgressView()
                            .controlSize(.small)
                        Spacer()
                    }
                    .padding(.vertical, 8)
                } else if let error = workspaceWithSessions.error {
                    Text(error)
                        .font(.caption)
                        .foregroundStyle(.red)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                } else if sessions.isEmpty {
                    Text("No sessions")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                } else {
                    VStack(alignment: .leading, spacing: 0) {
                        ForEach(sessions, id: \.id) { session in
                            sessionRow(session)
                                .transition(.opacity.combined(with: .move(edge: .top)))
                        }

                        if hiddenCount > 0 && !isFullyExpanded {
                            Button(action: onToggleFullExpand) {
                                Text("View \(hiddenCount) more sessions")
                                    .font(.caption)
                                    .foregroundStyle(.accent)
                            }
                            .buttonStyle(.plain)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 8)
                        } else if isFullyExpanded && hiddenCount > 0 {
                            Button(action: onToggleFullExpand) {
                                Text("Show less")
                                    .font(.caption)
                                    .foregroundStyle(.accent)
                            }
                            .buttonStyle(.plain)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 8)
                        }
                    }
                }
            }
        }
        .workspaceCardGlass(isActive: isActive)
        .workspaceCardGlassID(workspaceWithSessions.workspace.projectId, namespace: glassNamespace)
    }

    @ViewBuilder
    private func sessionRow(_ session: Session) -> some View {
        Button {
            onSelectSession(session.id)
        } label: {
            HStack(spacing: 8) {
                if session.id == activeSessionId {
                    Circle()
                        .fill(Color.accentColor)
                        .frame(width: 6, height: 6)
                } else {
                    Circle()
                        .fill(Color.clear)
                        .frame(width: 6, height: 6)
                }

                Text(session.title ?? session.id.prefix(8).description)
                    .font(.subheadline)
                    .foregroundStyle(session.id == activeSessionId ? .primary : .secondary)
                    .lineLimit(1)

                Spacer()
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Glass modifiers

private extension View {
    @ViewBuilder
    func workspaceCardGlass(isActive: Bool) -> some View {
        if #available(iOS 26, *) {
            if isActive {
                self.glassEffect(.regular.tint(.accentColor).interactive(), in: .rect(cornerRadius: 12))
            } else {
                self.glassEffect(.regular.interactive(), in: .rect(cornerRadius: 12))
            }
        } else {
            self.background(
                isActive ? Color.accentColor.opacity(0.08) : Color(.secondarySystemGroupedBackground),
                in: RoundedRectangle(cornerRadius: 12)
            )
        }
    }

    @ViewBuilder
    func workspaceCardGlassID(_ id: String, namespace: Namespace.ID) -> some View {
        if #available(iOS 26, *) {
            self.glassEffectID(id, in: namespace)
        } else {
            self
        }
    }
}
