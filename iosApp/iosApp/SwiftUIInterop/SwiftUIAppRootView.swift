import SwiftUI
import ComposeApp
import UIKit

private enum SwiftUIAppRoute: Hashable {
    case connect
    case settings
    case modelSelection
    case markdownFile(path: String, openId: Int64)
}

@MainActor
struct SwiftUIAppRootView: View {
    let onRequestAppReset: () -> Void

    @StateObject private var kmp = KmpAppOwnerStore()
    @State private var markdownManager = MarkdownFlowStoreManager()
    @State private var path: [SwiftUIAppRoute] = []
    @Environment(\.scenePhase) private var scenePhase
    @State private var settingsUiState: SettingsUiState?
    @StateObject private var sidebarUiStateEvents = KmpUiEventBridge<SidebarUiState>()
    @State private var sidebarUiState: SidebarUiState?

    @StateObject private var shareEvents = KmpUiEventBridge<SharePayload?>()
    @StateObject private var settingsUiStateEvents = KmpUiEventBridge<SettingsUiState>()
    @State private var pendingThemeVerification: Task<Void, Never>?
    @State private var desiredInterfaceStyle: UIUserInterfaceStyle = IosThemeApplier.readStoredStyle()
    @State private var showThemeRestartNotice: Bool = false
    @State private var isSidebarPresented: Bool = false

    private var activeSessionTitle: String? {
        sidebarUiState?.activeSessionTitle
    }

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
        let sidebarViewModel = kmp.owner.sidebarViewModel()

        ZStack(alignment: .leading) {
            NavigationStack(path: $path) {
                Group {
                    SwiftUIChatUIKitView(
                        viewModel: chatViewModel,
                        onOpenSettings: { path.append(.settings) },
                        onToggleSidebar: {
                            withAnimation(.snappy(duration: 0.28)) {
                                isSidebarPresented = true
                            }
                        },
                        onOpenFile: { openMarkdownFile($0) },
                        sessionTitle: activeSessionTitle,
                        workspacePath: settingsUiState?.activeWorkspaceWorktree
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
                            themeRestartNotice: $showThemeRestartNotice
                        )

                    case .modelSelection:
                        SwiftUIModelSelectionView(viewModel: settingsViewModel)

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

            if isSidebarPresented {
                NavigationStack {
                    WorkspacesSidebarView(
                        viewModel: sidebarViewModel,
                        onClose: {
                            withAnimation(.snappy(duration: 0.28)) {
                                isSidebarPresented = false
                            }
                        },
                        onSelectSession: {
                            withAnimation(.snappy(duration: 0.28)) {
                                isSidebarPresented = false
                            }
                        },
                        onRequestAppReset: {
                            isSidebarPresented = false
                            onRequestAppReset()
                        }
                    )
                }
                .background(Color(.systemBackground))
                .transition(.move(edge: .leading))
                .zIndex(1)
                .ignoresSafeArea()
            }
        }
        .onAppear {
            IosThemeApplier.apply(style: desiredInterfaceStyle)

            settingsUiStateEvents.start(flow: settingsViewModel.uiState) { uiState in
                settingsUiState = uiState

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

            sidebarUiStateEvents.start(flow: sidebarViewModel.uiState) { state in
                sidebarUiState = state
            }
        }
        .onDisappear {
            settingsUiStateEvents.stop()
            shareEvents.stop()
            sidebarUiStateEvents.stop()
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
        isSidebarPresented = false

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
