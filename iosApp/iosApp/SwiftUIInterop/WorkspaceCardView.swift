import SwiftUI
import ComposeApp

@MainActor
struct WorkspaceCardView: View {
    let workspaceWithSessions: WorkspaceWithSessions
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
        let last = (worktree as NSString).lastPathComponent
        return last.isEmpty ? workspaceWithSessions.workspace.projectId : last
    }

    private var sessions: [Session] {
        let all = workspaceWithSessions.sessions
        if isFullyExpanded { return Array(all) }
        return Array(all.prefix(3))
    }

    private var hiddenCount: Int {
        max(0, Int(workspaceWithSessions.sessions.count) - 3)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Workspace header
            HStack(spacing: 10) {
                Image(systemName: "folder")
                    .foregroundStyle(.secondary)
                    .font(.body)

                Text(displayTitle)
                    .font(.system(.body, design: .default).weight(.medium))
                    .foregroundStyle(.primary)
                    .lineLimit(1)

                Spacer(minLength: 0)

                if isCreatingSession {
                    ProgressView()
                        .controlSize(.small)
                } else {
                    Button(action: onCreateSession) {
                        Image(systemName: "plus")
                            .font(.system(.subheadline, weight: .medium))
                            .foregroundStyle(.secondary)
                            .frame(width: 28, height: 28)
                            .background(Color(.tertiarySystemFill), in: Circle())
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 16)
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
                    .padding(.vertical, 10)
                } else if let error = workspaceWithSessions.error {
                    Text(error)
                        .font(.caption)
                        .foregroundStyle(.red)
                        .padding(.horizontal, 16)
                        .padding(.bottom, 10)
                } else if sessions.isEmpty {
                    Text("No sessions")
                        .font(.subheadline)
                        .foregroundStyle(.tertiary)
                        .padding(.horizontal, 16)
                        .padding(.bottom, 10)
                } else {
                    VStack(alignment: .leading, spacing: 2) {
                        ForEach(sessions, id: \.id) { session in
                            sessionRow(session)
                                .transition(.opacity.combined(with: .move(edge: .top)))
                        }

                        if hiddenCount > 0 && !isFullyExpanded {
                            Button(action: onToggleFullExpand) {
                                Text("View \(hiddenCount) more")
                                    .font(.caption)
                                    .foregroundStyle(Color.accentColor)
                            }
                            .buttonStyle(.plain)
                            .padding(.horizontal, 16)
                            .padding(.vertical, 8)
                        } else if isFullyExpanded && hiddenCount > 0 {
                            Button(action: onToggleFullExpand) {
                                Text("Show less")
                                    .font(.caption)
                                    .foregroundStyle(Color.accentColor)
                            }
                            .buttonStyle(.plain)
                            .padding(.horizontal, 16)
                            .padding(.vertical, 8)
                        }
                    }
                    .padding(.bottom, 4)
                }
            }
        }
    }

    @ViewBuilder
    private func sessionRow(_ session: Session) -> some View {
        let isActiveSession = session.id == activeSessionId

        Button {
            onSelectSession(session.id)
        } label: {
            HStack(spacing: 8) {
                if isActiveSession {
                    Circle()
                        .fill(Color.accentColor)
                        .frame(width: 6, height: 6)
                }

                Text(session.title ?? String(session.id.prefix(8)))
                    .font(.subheadline)
                    .foregroundStyle(isActiveSession ? .primary : .secondary)
                    .lineLimit(1)

                Spacer(minLength: 0)

                Text(relativeTime(session.updatedAt))
                    .font(.caption)
                    .foregroundStyle(.tertiary)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .background(
                isActiveSession
                    ? Color(.tertiarySystemFill)
                    : Color.clear,
                in: RoundedRectangle(cornerRadius: 8)
            )
            .padding(.horizontal, 8)
        }
        .buttonStyle(.plain)
    }

    private func relativeTime(_ instant: KotlinInstant) -> String {
        let epochMs = instant.toEpochMilliseconds()
        let date = Date(timeIntervalSince1970: TimeInterval(epochMs) / 1000.0)
        let interval = Date().timeIntervalSince(date)

        if interval < 60 { return "now" }
        if interval < 3600 { return "\(Int(interval / 60))m" }
        if interval < 86400 { return "\(Int(interval / 3600))h" }
        if interval < 604800 { return "\(Int(interval / 86400))d" }
        return "\(Int(interval / 604800))w"
    }
}
