import SwiftUI
import ComposeApp

@MainActor
struct SwiftUIChatUIKitView: View {
    let viewModel: ChatViewModel

    let onOpenSettings: () -> Void
    let onOpenSessions: () -> Void
    let onOpenFile: (String) -> Void

    @StateObject private var store: ChatViewControllerStore
    @StateObject private var uiStateEvents = KmpUiEventBridge<ChatUiState>()
    @State private var isRefreshing: Bool = false

    init(
        viewModel: ChatViewModel,
        onOpenSettings: @escaping () -> Void,
        onOpenSessions: @escaping () -> Void,
        onOpenFile: @escaping (String) -> Void
    ) {
        self.viewModel = viewModel
        self.onOpenSettings = onOpenSettings
        self.onOpenSessions = onOpenSessions
        self.onOpenFile = onOpenFile

        _store = StateObject(wrappedValue: ChatViewControllerStore(viewModel: viewModel))
    }

    var body: some View {
        ChatUIKitContainerView(
            controller: store.controller,
            onOpenFile: onOpenFile
        )
        .ignoresSafeArea(.keyboard)
        .navigationTitle("OpenCode")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(.ultraThinMaterial, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .onAppear {
            uiStateEvents.start(flow: viewModel.uiState) { state in
                isRefreshing = state.isRefreshing
            }
        }
        .onDisappear {
            uiStateEvents.stop()
            isRefreshing = false
        }
        .toolbar {
            ToolbarItemGroup(placement: .navigationBarTrailing) {
                Button(action: viewModel.retry) {
                    if isRefreshing {
                        ProgressView()
                            .controlSize(.small)
                    } else {
                        Image(systemName: "arrow.clockwise")
                    }
                }
                .disabled(isRefreshing)

                Button(action: onOpenSessions) {
                    Image(systemName: "rectangle.stack")
                }

                Button(action: onOpenSettings) {
                    Image(systemName: "gearshape")
                }
            }
        }
    }
}

@MainActor
private final class ChatViewControllerStore: ObservableObject {
    let controller: ChatViewController

    init(viewModel: ChatViewModel) {
        // SwiftUI requires an initialized controller during `StateObject` creation.
        // `ChatUIKitContainerView.updateUIViewController` replaces these no-ops with real closures
        // before any user interaction can occur.
        controller = ChatViewController(
            viewModel: viewModel,
            onOpenFile: { _ in }
        )
    }
}

private struct ChatUIKitContainerView: UIViewControllerRepresentable {
    let controller: ChatViewController
    let onOpenFile: (String) -> Void

    func makeUIViewController(context: Context) -> ChatViewController {
        controller
    }

    func updateUIViewController(_ uiViewController: ChatViewController, context: Context) {
        uiViewController.onOpenFile = onOpenFile
    }
}
