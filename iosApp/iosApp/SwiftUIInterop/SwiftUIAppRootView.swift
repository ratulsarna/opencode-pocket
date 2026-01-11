import SwiftUI
import ComposeApp
import UIKit

private enum SwiftUIAppRoute: Hashable {
    case connect
    case settings
    case modelSelection
    case workspaces
    case markdownFile(path: String, openId: Int64)
}

@MainActor
struct SwiftUIAppRootView: View {
    let onRequestAppReset: () -> Void

    @StateObject private var kmp = KmpAppOwnerStore()
    @State private var markdownManager = MarkdownFlowStoreManager()
    @State private var path: [SwiftUIAppRoute] = []
    @Environment(\.scenePhase) private var scenePhase
    @State private var isShowingSessions: Bool = false

    @StateObject private var shareEvents = KmpUiEventBridge<SharePayload?>()
    @StateObject private var settingsUiStateEvents = KmpUiEventBridge<SettingsUiState>()
    @State private var pendingThemeVerification: Task<Void, Never>?
    @State private var desiredInterfaceStyle: UIUserInterfaceStyle = IosThemeApplier.readStoredStyle()
    @State private var showThemeRestartNotice: Bool = false

    var body: some View {
        let connectViewModel = kmp.owner.connectViewModel()

        Observing(connectViewModel.uiState) {
            SamFullScreenLoadingView(title: "Loading…")
        } content: { connectState in
            let isPaired = AppModule.shared.isMockMode || connectState.hasAuthTokenForActiveServer
            Group {
                if isPaired {
                    pairedAppView(connectViewModel: connectViewModel)
                } else {
                    NavigationStack {
                        SwiftUIConnectToOpenCodeView(
                            viewModel: connectViewModel,
                            onConnected: { onRequestAppReset() },
                            onDisconnected: { onRequestAppReset() }
                        )
                    }
                }
            }
            .preferredColorScheme(preferredColorSchemeOverride(for: desiredInterfaceStyle))
        }
    }

    @ViewBuilder
    private func pairedAppView(connectViewModel: ConnectViewModel) -> some View {
        let chatViewModel = kmp.owner.chatViewModel()
        let settingsViewModel = kmp.owner.settingsViewModel()
        let workspacesViewModel = kmp.owner.workspacesViewModel()

        NavigationStack(path: $path) {
            Group {
                SwiftUIChatUIKitView(
                    viewModel: chatViewModel,
                    onOpenSettings: { path.append(.settings) },
                    onOpenSessions: { isShowingSessions = true },
                    onOpenFile: { openMarkdownFile($0) }
                )
            }
            .navigationDestination(for: SwiftUIAppRoute.self) { route in
                switch route {
                case .connect:
                    SwiftUIConnectToOpenCodeView(
                        viewModel: connectViewModel,
                        onConnected: { onRequestAppReset() },
                        onDisconnected: { onRequestAppReset() }
                    )

                case .settings:
                    SwiftUISettingsView(
                        viewModel: settingsViewModel,
                        onOpenConnect: { path.append(.connect) },
                        onOpenModelSelection: { path.append(.modelSelection) },
                        onOpenWorkspaces: { path.append(.workspaces) },
                        onOpenSessions: { isShowingSessions = true },
                        themeRestartNotice: $showThemeRestartNotice
                    )

                case .modelSelection:
                    SwiftUIModelSelectionView(viewModel: settingsViewModel)

                case .workspaces:
                    SwiftUIWorkspacesView(
                        viewModel: workspacesViewModel,
                        onDidSwitchWorkspace: { onRequestAppReset() }
                    )

                case .markdownFile(let filePath, let openId):
                    let key = MarkdownRouteKey(path: filePath, openId: openId)
                    if let store = markdownManager.stores[key] {
                        SwiftUIMarkdownFileViewerView(
                            viewModel: store.owner.markdownFileViewerViewModel(path: filePath, openId: openId),
                            onOpenFile: { openMarkdownFile($0) }
                        )
                    } else {
                        SamFullScreenLoadingView(title: "Opening file…")
                            .task {
                                markdownManager.ensureStore(for: key)
                            }
                    }
                }
            }
        }
        .sheet(isPresented: $isShowingSessions) {
            SwiftUISessionsSheetView()
        }
        .onAppear {
            // Apply a best-effort theme override on first appearance. Launch-time application happens in `iOSApp`,
            // but we re-apply here in case the window did not exist yet.
            IosThemeApplier.apply(style: desiredInterfaceStyle)

            settingsUiStateEvents.start(flow: settingsViewModel.uiState) { uiState in
                let newStyle = IosThemeApplier.style(fromStoredString: uiState.selectedThemeMode.name)
                if newStyle == desiredInterfaceStyle { return }

                desiredInterfaceStyle = newStyle
                showThemeRestartNotice = false
                IosThemeApplier.apply(style: newStyle)

                pendingThemeVerification?.cancel()
                pendingThemeVerification = Task { @MainActor in
                    do {
                        try await Task.sleep(nanoseconds: 250_000_000)
                    } catch {
                        return
                    }
                    if Task.isCancelled { return }
                    if IosThemeApplier.anyWindowMismatched(expected: newStyle) {
                        showThemeRestartNotice = true
                    }
                }
            }

            shareEvents.start(flow: ShareExtensionBridge.shared.pendingPayload) { payload in
                handleSharePayload(payload, chatViewModel: chatViewModel)
            }
        }
        .onDisappear {
            settingsUiStateEvents.stop()
            shareEvents.stop()
            pendingThemeVerification?.cancel()
            pendingThemeVerification = nil
        }
        .onChange(of: path) { newValue in
            let activeMarkdownKeys = Set(newValue.compactMap { route -> MarkdownRouteKey? in
                if case let .markdownFile(filePath, openId) = route {
                    return MarkdownRouteKey(path: filePath, openId: openId)
                }
                return nil
            })
            markdownManager.prune(activeKeys: activeMarkdownKeys)
        }
        .onChange(of: scenePhase) { newPhase in
            switch newPhase {
            case .active:
                chatViewModel.onAppForegrounded()
            case .background:
                chatViewModel.onAppBackgrounded()
            case .inactive:
                break
            @unknown default:
                break
            }
        }
    }

    private func preferredColorSchemeOverride(for style: UIUserInterfaceStyle) -> ColorScheme? {
        switch style {
        case .light:
            return .light
        case .dark:
            return .dark
        default:
            return nil
        }
    }

    private func handleSharePayload(_ payload: SharePayload?, chatViewModel: ChatViewModel) {
        guard let payload else { return }

        path = []

        payload.attachments.forEach { attachment in
            chatViewModel.addAttachment(attachment: attachment)
        }
        if let text = payload.text, !text.isEmpty {
            chatViewModel.setInputText(text: text)
        }

        ShareExtensionBridge.shared.clearPendingPayload()
    }

    private func openMarkdownFile(_ filePath: String) {
        let key = markdownManager.openFile(path: filePath)
        path.append(.markdownFile(path: key.path, openId: key.openId))
    }
}

struct SamFullScreenLoadingView: View {
    var title: String = "Loading…"
    var subtitle: String? = nil

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [
                    Color.accentColor.opacity(0.18),
                    Color(.systemBackground),
                    Color(.systemBackground),
                ],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            .ignoresSafeArea()

            VStack(spacing: 14) {
                ZStack {
                    RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .fill(.ultraThinMaterial)
                    Image(systemName: "sparkles")
                        .font(.system(size: 22, weight: .semibold))
                        .foregroundStyle(.secondary)
                }
                .frame(width: 56, height: 56)

                Text(title)
                    .font(.title3.weight(.semibold))
                    .multilineTextAlignment(.center)

                if let subtitle, !subtitle.isEmpty {
                    Text(subtitle)
                        .font(.body)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }

                ProgressView()
                    .controlSize(.large)
                    .padding(.top, 6)
            }
            .padding(28)
            .frame(maxWidth: 520)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(Text(subtitle == nil ? title : "\(title) \(subtitle ?? "")"))
    }
}
