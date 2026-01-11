import SwiftUI
import ComposeApp

@MainActor
struct SwiftUISettingsView: View {
    let viewModel: SettingsViewModel
    let onOpenConnect: () -> Void
    let onOpenModelSelection: () -> Void
    let onOpenWorkspaces: () -> Void
    let onOpenSessions: () -> Void
    @Binding var themeRestartNotice: Bool

    @State private var isShowingAgentSheet = false

    var body: some View {
        Observing(viewModel.uiState) {
            SamFullScreenLoadingView(title: "Loading settings…")
        } content: { uiState in
            List {
                Section {
                    HStack(spacing: 12) {
                        StatusPill(
                            title: uiState.isConnected ? "Connected" : "Disconnected",
                            systemImage: uiState.isConnected ? "checkmark.circle.fill" : "xmark.octagon.fill",
                            tint: uiState.isConnected ? .green : .red
                        )

                        StatusPill(
                            title: contextUsageText(uiState.contextUsage),
                            systemImage: "memorychip",
                            tint: .secondary
                        )
                    }
                    .listRowInsets(EdgeInsets())
                    .listRowSeparator(.hidden)
                    .listRowBackground(Color.clear)
                    .padding(.vertical, 4)
                }

                Section("App") {
                    Button(action: onOpenConnect) {
                        HStack {
                            VStack(alignment: .leading, spacing: 4) {
                                Text("Connect to OpenCode")
                                    .foregroundColor(.primary)
                                Group {
                                    if uiState.isConnected {
                                        Text("Connected to \(uiState.activeServerName ?? "OpenCode"). Tap to switch or disconnect.")
                                    } else {
                                        Text("Scan a QR code or paste a pairing string")
                                    }
                                }
                                .font(.caption)
                                .foregroundColor(.secondary)
                            }
                            Spacer()
                            Image(systemName: "chevron.right")
                                .foregroundColor(.secondary)
                                .font(.caption)
                        }
                    }
                    .buttonStyle(.plain)

                    Button(action: onOpenWorkspaces) {
                        HStack {
                            VStack(alignment: .leading, spacing: 4) {
                                Text("Workspace")
                                    .foregroundColor(.primary)
                                Text(workspaceText(name: uiState.activeWorkspaceName, worktree: uiState.activeWorkspaceWorktree))
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                            Spacer()
                            Image(systemName: "chevron.right")
                                .foregroundColor(.secondary)
                                .font(.caption)
                        }
                    }
                    .buttonStyle(.plain)

                    Button(action: onOpenModelSelection) {
                        HStack {
                            VStack(alignment: .leading, spacing: 4) {
                                Text("Model")
                                    .foregroundColor(.primary)
                                Text(selectedModelText(providers: uiState.providers, selectedModel: uiState.selectedModel))
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                            Spacer()
                            if uiState.isLoadingModels {
                                ProgressView()
                            } else {
                                Image(systemName: "chevron.right")
                                    .foregroundColor(.secondary)
                                    .font(.caption)
                            }
                        }
                    }
                    .disabled(uiState.isLoadingModels || uiState.modelError != nil)
                    .buttonStyle(.plain)

                    if let modelError = uiState.modelError, !modelError.isEmpty {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(modelError)
                                .foregroundStyle(.red)
                                .font(.caption)
                            Button("Retry") { viewModel.refreshModels() }
                                .font(.caption)
                        }
                    }

                    Button {
                        isShowingAgentSheet = true
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 4) {
                                Text("Agent")
                                    .foregroundColor(.primary)
                                Text(selectedAgentText(uiState))
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                            Spacer()
                            if uiState.isLoadingAgents {
                                ProgressView()
                            } else {
                                Image(systemName: "chevron.right")
                                    .foregroundColor(.secondary)
                                    .font(.caption)
                            }
                        }
                    }
                    .disabled(uiState.isLoadingAgents || uiState.agentError != nil)
                    .buttonStyle(.plain)

                    if let agentError = uiState.agentError, !agentError.isEmpty {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(agentError)
                                .foregroundStyle(.red)
                                .font(.caption)
                            Button("Retry") { viewModel.refreshAgents() }
                                .font(.caption)
                        }
                    }

                    Picker(
                        "Theme",
                        selection: Binding(
                            get: { ThemeChoice.from(uiState.selectedThemeMode) },
                            set: { viewModel.selectThemeMode(mode: $0.themeMode) }
                        )
                    ) {
                        ForEach(ThemeChoice.allCases) { choice in
                            Text(choice.label).tag(choice)
                        }
                    }
                    .tint(.secondary)

                    if themeRestartNotice {
                        Text("Theme changes require restarting the app to fully apply.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }

                Section("Navigation") {
                    Button(action: onOpenSessions) {
                        NavigationRowLabel(title: "Sessions", systemImage: "rectangle.stack")
                    }
                }

                Section("Advanced") {
                    Toggle(
                        "Always expand assistant details",
                        isOn: Binding(
                            get: { uiState.alwaysExpandAssistantParts },
                            set: { viewModel.setAlwaysExpandAssistantParts(alwaysExpand: $0) }
                        )
                    )

                    Text("Reasoning and tool parts start expanded by default. Does not affect Sessions list.")
                        .font(.caption)
                        .foregroundStyle(.secondary)

                    Picker(
                        "Assistant response details",
                        selection: Binding(
                            get: { AssistantResponseDetailsChoice.from(uiState.assistantResponseVisibility.preset) },
                            set: { viewModel.setAssistantResponsePresetName(name: $0.rawValue) }
                        )
                    ) {
                        ForEach(AssistantResponseDetailsChoice.allCases) { choice in
                            Text(choice.label).tag(choice)
                        }
                    }
                    .tint(.secondary)

                    Text("Text and file attachments are always shown. Use Custom to hide/show thinking, tools, and other internals.")
                        .font(.caption)
                        .foregroundStyle(.secondary)

                    if uiState.assistantResponseVisibility.preset.name.uppercased() == "CUSTOM" {
                        let effective = uiState.assistantResponseVisibility.effective()

                        Toggle(
                            "Show thinking",
                            isOn: Binding(
                                get: { effective.showReasoning },
                                set: { viewModel.setAssistantResponseShowThinking(show: $0) }
                            )
                        )

                        Toggle(
                            "Show tools",
                            isOn: Binding(
                                get: { effective.showTools },
                                set: { viewModel.setAssistantResponseShowTools(show: $0) }
                            )
                        )

                        Toggle(
                            "Show patches",
                            isOn: Binding(
                                get: { effective.showPatches },
                                set: { viewModel.setAssistantResponseShowPatches(show: $0) }
                            )
                        )

                        Toggle(
                            "Show agent delegation",
                            isOn: Binding(
                                get: { effective.showAgents },
                                set: { viewModel.setAssistantResponseShowAgentDelegation(show: $0) }
                            )
                        )

                        Toggle(
                            "Show retries",
                            isOn: Binding(
                                get: { effective.showRetries },
                                set: { viewModel.setAssistantResponseShowRetries(show: $0) }
                            )
                        )

                        Toggle(
                            "Show compaction",
                            isOn: Binding(
                                get: { effective.showCompactions },
                                set: { viewModel.setAssistantResponseShowCompaction(show: $0) }
                            )
                        )

                        Toggle(
                            "Show unknown parts",
                            isOn: Binding(
                                get: { effective.showUnknowns },
                                set: { viewModel.setAssistantResponseShowUnknownParts(show: $0) }
                            )
                        )
                    }

                }
            }
            .sheet(isPresented: $isShowingAgentSheet) {
                NavigationStack {
                    AgentSelectionSheet(
                        uiState: uiState,
                        onSelect: { name in
                            viewModel.selectAgent(agentName: name)
                            isShowingAgentSheet = false
                        },
                        onRefresh: viewModel.refreshAgents
                    )
                    .navigationTitle("Select Agent")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .topBarTrailing) {
                            Button("Done") {
                                isShowingAgentSheet = false
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.large)
    }

    private func selectedAgentText(_ uiState: SettingsUiState) -> String {
        if uiState.isLoadingAgents { return "Loading…" }
        if let error = uiState.agentError, !error.isEmpty { return "Error" }
        if let selected = uiState.selectedAgentName, !selected.isEmpty { return selected }
        return "Select an agent"
    }

    private func selectedModelText(providers: [Provider], selectedModel: SelectedModel?) -> String {
        if let selectedModel {
            if let provider = providers.first(where: { $0.id == selectedModel.providerId }),
               let model = provider.models.first(where: { $0.id == selectedModel.modelId }) {
                return "\(provider.name) / \(model.name)"
            }
            return "\(selectedModel.providerId) / \(selectedModel.modelId)"
        }
        return "Select a model"
    }

    private func contextUsageText(_ usage: ContextUsage) -> String {
        guard let percent = usage.percentage?.floatValue else { return "Context: --" }
        let display = Int(percent * 100.0)
        return "Context: \(display)%"
    }

    private func serverText(name: String?, baseUrl: String?) -> String {
        let serverName = (name ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let url = (baseUrl ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if serverName.isEmpty && url.isEmpty {
            return "Not set"
        }

        let endpoint = url
            .replacingOccurrences(of: "http://", with: "")
            .replacingOccurrences(of: "https://", with: "")
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))

        if serverName.isEmpty {
            return endpoint
        }
        if endpoint.isEmpty {
            return serverName
        }
        return "\(serverName) (\(endpoint))"
    }

    private func workspaceText(name: String?, worktree: String?) -> String {
        let workspaceName = (name ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let path = (worktree ?? "").trimmingCharacters(in: .whitespacesAndNewlines)

        if path.isEmpty {
            return "Server default (cwd)"
        }

        if workspaceName.isEmpty {
            return path
        }

        return "\(workspaceName) · \(path)"
    }
}

private struct NavigationRowLabel: View {
    let title: String
    let systemImage: String

    var body: some View {
        HStack {
            Text(title)
                .foregroundColor(.primary)
            Spacer()
            Image(systemName: "chevron.right")
                .foregroundColor(.secondary)
                .font(.caption)
        }
    }
}

private struct StatusPill: View {
    let title: String
    let systemImage: String
    let tint: Color

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: systemImage)
                .foregroundStyle(tint)
            Text(title)
                .font(.caption)
                .foregroundStyle(.primary)
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .background(Color(uiColor: .tertiarySystemBackground))
        .overlay(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .stroke(Color(uiColor: .separator).opacity(0.35), lineWidth: 0.5)
        )
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}

private struct AgentSelectionSheet: View {
    let uiState: SettingsUiState
    let onSelect: (String) -> Void
    let onRefresh: () -> Void

    var body: some View {
        Group {
            if uiState.isLoadingAgents {
                VStack {
                    ProgressView()
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if let error = uiState.agentError, !error.isEmpty {
                VStack(spacing: 12) {
                    Text(error)
                        .foregroundStyle(.red)
                    Button("Retry") { onRefresh() }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if uiState.agents.isEmpty {
                VStack(spacing: 8) {
                    Text("No agents available")
                        .foregroundStyle(.secondary)
                    Button("Refresh") { onRefresh() }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List {
                    ForEach(uiState.agents, id: \.name) { agent in
                        Button {
                            onSelect(agent.name)
                        } label: {
                            HStack(alignment: .top, spacing: 12) {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(agent.name)
                                        .font(.headline)
                                    if !agent.description_.isEmpty {
                                        Text(agent.description_)
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                            .lineLimit(2)
                                    }
                                }
                                Spacer()
                                if uiState.selectedAgentName == agent.name {
                                    Image(systemName: "checkmark")
                                        .foregroundStyle(.tint)
                                }
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }
                .listStyle(.plain)
            }
        }
    }
}

private enum ThemeChoice: String, CaseIterable, Identifiable {
    case system
    case light
    case dark

    var id: String { rawValue }

    var label: String {
        switch self {
        case .system: return "System"
        case .light: return "Light"
        case .dark: return "Dark"
        }
    }

    var themeMode: ThemeMode {
        switch self {
        case .system: return ThemeMode.system
        case .light: return ThemeMode.light
        case .dark: return ThemeMode.dark
        }
    }

    static func from(_ mode: ThemeMode) -> ThemeChoice {
        switch mode.name.uppercased() {
        case "LIGHT": return .light
        case "DARK": return .dark
        default: return .system
        }
    }
}

private enum AssistantResponseDetailsChoice: String, CaseIterable, Identifiable {
    case default_ = "DEFAULT"
    case textAndThinking = "TEXT_AND_THINKING"
    case textOnly = "TEXT_ONLY"
    case all = "ALL"
    case custom = "CUSTOM"

    var id: String { rawValue }

    var label: String {
        switch self {
        case .default_: return "Default"
        case .textAndThinking: return "Text + thinking"
        case .textOnly: return "Text only"
        case .all: return "All"
        case .custom: return "Custom"
        }
    }

    static func from(_ preset: AssistantResponseVisibilityPreset) -> AssistantResponseDetailsChoice {
        switch preset.name.uppercased() {
        case "TEXT_ONLY": return .textOnly
        case "TEXT_AND_THINKING": return .textAndThinking
        case "ALL": return .all
        case "CUSTOM": return .custom
        default: return .default_
        }
    }
}

@MainActor
struct SwiftUIModelSelectionView: View {
    let viewModel: SettingsViewModel

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        Observing(viewModel.uiState) {
            SamFullScreenLoadingView(title: "Loading models…")
        } content: { uiState in
            List {
                let favoriteKeys = Set(uiState.favoriteModels.map { "\($0.providerId):\($0.modelId)" })
                let favorites = resolvedFavoriteModels(
                    providers: uiState.providers,
                    favoriteModels: uiState.favoriteModels,
                    query: uiState.modelSearchQuery
                )
                let sections = filteredProviderSections(
                    providers: uiState.providers,
                    query: uiState.modelSearchQuery,
                    excluding: favoriteKeys
                )

                if favorites.isEmpty && sections.isEmpty {
                    Text(uiState.modelSearchQuery.isEmpty ? "No models available" : "No models found matching \"\(uiState.modelSearchQuery)\"")
                        .foregroundStyle(.secondary)
                } else {
                    if !favorites.isEmpty {
                        Section("Favorites") {
                            ForEach(favorites) { favorite in
                                ModelRow(
                                    title: favorite.modelName,
                                    subtitle: favorite.providerName,
                                    isFavorited: true,
                                    isSelected: uiState.selectedModel?.providerId == favorite.providerId &&
                                        uiState.selectedModel?.modelId == favorite.modelId,
                                    onSelect: {
                                        viewModel.selectModel(providerId: favorite.providerId, modelId: favorite.modelId)
                                        viewModel.updateModelSearchQuery(query: "")
                                        dismiss()
                                    },
                                    onToggleFavorite: {
                                        viewModel.toggleFavoriteModel(providerId: favorite.providerId, modelId: favorite.modelId)
                                    }
                                )
                            }
                        }
                    }

                    ForEach(sections) { section in
                        Section(section.name) {
                            ForEach(section.models, id: \.id) { model in
                                ModelRow(
                                    title: model.name,
                                    subtitle: nil,
                                    isFavorited: favoriteKeys.contains("\(section.id):\(model.id)"),
                                    isSelected: uiState.selectedModel?.providerId == section.id &&
                                        uiState.selectedModel?.modelId == model.id,
                                    onSelect: {
                                        viewModel.selectModel(providerId: section.id, modelId: model.id)
                                        viewModel.updateModelSearchQuery(query: "")
                                        dismiss()
                                    },
                                    onToggleFavorite: {
                                        viewModel.toggleFavoriteModel(providerId: section.id, modelId: model.id)
                                    }
                                )
                            }
                        }
                    }
                }
            }
            .searchable(
                text: Binding(
                    get: { uiState.modelSearchQuery },
                    set: { viewModel.updateModelSearchQuery(query: $0) }
                ),
                placement: .navigationBarDrawer(displayMode: .always),
                prompt: "Search models…"
            )
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    if !uiState.modelSearchQuery.isEmpty {
                        Button("Clear") {
                            viewModel.updateModelSearchQuery(query: "")
                        }
                    }
                }
            }
            .onDisappear {
                viewModel.updateModelSearchQuery(query: "")
            }
        }
        .navigationTitle("Select Model")
        .navigationBarTitleDisplayMode(.inline)
    }

    private struct ProviderSection: Identifiable {
        let id: String
        let name: String
        let models: [Model]
    }

    private struct FavoriteModel: Identifiable {
        let id: String
        let providerId: String
        let modelId: String
        let providerName: String
        let modelName: String
    }

    private struct ModelRow: View {
        let title: String
        let subtitle: String?
        let isFavorited: Bool
        let isSelected: Bool
        let onSelect: () -> Void
        let onToggleFavorite: () -> Void

        var body: some View {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                    if let subtitle, !subtitle.isEmpty {
                        Text(subtitle)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }

                Spacer()

                Button(action: onToggleFavorite) {
                    Image(systemName: isFavorited ? "star.fill" : "star")
                        .foregroundStyle(isFavorited ? Color.accentColor : Color.secondary)
                }
                .buttonStyle(.borderless)

                if isSelected {
                    Image(systemName: "checkmark")
                        .foregroundStyle(.tint)
                }
            }
            .contentShape(Rectangle())
            .onTapGesture(perform: onSelect)
        }
    }

    private func resolvedFavoriteModels(
        providers: [Provider],
        favoriteModels: [SelectedModel],
        query: String
    ) -> [FavoriteModel] {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        let lowerQuery = trimmed.lowercased()

        let resolved = favoriteModels.compactMap { favorite -> FavoriteModel? in
            guard let provider = providers.first(where: { $0.id == favorite.providerId }) else { return nil }
            guard let model = provider.models.first(where: { $0.id == favorite.modelId }) else { return nil }
            return FavoriteModel(
                id: "\(provider.id):\(model.id)",
                providerId: provider.id,
                modelId: model.id,
                providerName: provider.name,
                modelName: model.name
            )
        }

        let filtered = lowerQuery.isEmpty
            ? resolved
            : resolved.filter { $0.modelName.lowercased().contains(lowerQuery) }

        return filtered.sorted { lhs, rhs in
            let provider = lhs.providerName.localizedCaseInsensitiveCompare(rhs.providerName)
            if provider != .orderedSame { return provider == .orderedAscending }
            return lhs.modelName.localizedCaseInsensitiveCompare(rhs.modelName) == .orderedAscending
        }
    }

    private func filteredProviderSections(
        providers: [Provider],
        query: String,
        excluding excludedKeys: Set<String>
    ) -> [ProviderSection] {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            return providers.compactMap { provider in
                let models = provider.models.filter { !excludedKeys.contains("\(provider.id):\($0.id)") }
                guard !models.isEmpty else { return nil }
                return ProviderSection(id: provider.id, name: provider.name, models: models)
            }
        }

        let lower = trimmed.lowercased()
        return providers.compactMap { provider in
            let models = provider.models.filter { model in
                model.name.lowercased().contains(lower) &&
                    !excludedKeys.contains("\(provider.id):\(model.id)")
            }
            guard !models.isEmpty else { return nil }
            return ProviderSection(id: provider.id, name: provider.name, models: models)
        }
    }
}
