import SwiftUI
import UIKit
import ComposeApp

@MainActor
struct SwiftUIChatUIKitView: View {
    let viewModel: ChatViewModel

    let onOpenSettings: () -> Void
    let onToggleSidebar: () -> Void
    let onOpenFile: (String) -> Void
    let sessionTitle: String?
    let workspacePath: String?

    @StateObject private var store: ChatViewControllerStore
    @StateObject private var uiStateEvents = KmpUiEventBridge<ChatUiState>()
    @State private var latestUiState: ChatUiState?
    @State private var topOverlayHeight: CGFloat = 0
    @State private var bottomOverlayHeight: CGFloat = 0

    init(
        viewModel: ChatViewModel,
        onOpenSettings: @escaping () -> Void,
        onToggleSidebar: @escaping () -> Void,
        onOpenFile: @escaping (String) -> Void,
        sessionTitle: String?,
        workspacePath: String?
    ) {
        self.viewModel = viewModel
        self.onOpenSettings = onOpenSettings
        self.onToggleSidebar = onToggleSidebar
        self.onOpenFile = onOpenFile
        self.sessionTitle = sessionTitle
        self.workspacePath = workspacePath

        _store = StateObject(wrappedValue: ChatViewControllerStore(viewModel: viewModel))
    }

    private var attachmentErrorKey: String? {
        guard let error = latestUiState?.attachmentError else { return nil }
        return ChatAttachmentErrorPresentation.key(error)
    }

    var body: some View {
        GeometryReader { proxy in
            ZStack {
                ChatScreenBackground()

                ChatUIKitContainerView(
                    controller: store.controller,
                    onOpenFile: onOpenFile,
                    topInset: topOverlayHeight,
                    bottomInset: bottomOverlayHeight
                )

                if let state = latestUiState {
                    VStack(spacing: 0) {
                        toolbarOverlay(state: state, safeTopInset: proxy.safeAreaInsets.top)
                        Spacer(minLength: 0)
                        composerOverlay(state: state, safeBottomInset: keyboardAwareBottomInset(proxy.safeAreaInsets.bottom))
                    }
                    .zIndex(1)
                }
            }
            .ignoresSafeArea(.container)
        }
        .toolbar(.hidden, for: .navigationBar)
        .onAppear {
            uiStateEvents.start(flow: viewModel.uiState) { state in
                latestUiState = state
            }
        }
        .onDisappear {
            uiStateEvents.stop()
        }
        .task(id: attachmentErrorKey) {
            guard attachmentErrorKey != nil else { return }

            do {
                try await Task.sleep(nanoseconds: 2_750_000_000)
            } catch {
                return
            }

            guard !Task.isCancelled else { return }
            viewModel.dismissAttachmentError()
        }
    }

    private func addPastedImages(_ imageDataItems: [ChatComposerPastedImagePayload]) {
        guard !imageDataItems.isEmpty else { return }

        for item in imageDataItems {
            let attachment = ChatAttachmentBuilder.makeAttachment(
                filename: item.filename,
                mimeType: item.mimeType,
                data: item.data
            )
            viewModel.addAttachment(attachment: attachment)
        }
    }

    private func keyboardAwareBottomInset(_ safeAreaBottom: CGFloat) -> CGFloat {
        if safeAreaBottom > 80 {
            return 6
        }
        return max(safeAreaBottom, 10)
    }

    @ViewBuilder
    private func toolbarOverlay(state: ChatUiState, safeTopInset: CGFloat) -> some View {
        ChatToolbarGlassView(
            state: state,
            isRefreshing: state.isRefreshing,
            onRetry: viewModel.retry,
            onToggleSidebar: onToggleSidebar,
            onOpenSettings: onOpenSettings,
            onDismissError: viewModel.dismissError,
            onRevert: viewModel.revertToLastGood,
            sessionTitle: sessionTitle,
            workspacePath: workspacePath
        )
        .padding(.horizontal, 12)
        .padding(.top, safeTopInset + 2)
        .padding(.bottom, 3)
        .chatGlassHostRegion(cornerRadius: 34, tint: .clear)
        .background(HeightReader(height: $topOverlayHeight))
    }

    @ViewBuilder
    private func composerOverlay(state: ChatUiState, safeBottomInset: CGFloat) -> some View {
        VStack(spacing: 8) {
            if let attachmentError = state.attachmentError {
                ChatAttachmentErrorView(
                    message: ChatAttachmentErrorPresentation.message(attachmentError),
                    onDismiss: viewModel.dismissAttachmentError
                )
            }

            ChatComposerCardView(
                state: state,
                onPickPhotos: store.controller.presentPhotoPicker,
                onPickFiles: store.controller.presentDocumentPicker,
                onAddFromClipboard: viewModel.addFromClipboard,
                onRemoveAttachment: viewModel.removeAttachment,
                onSelectMentionSuggestion: viewModel.selectMentionSuggestion,
                onSelectSlashCommandSuggestion: viewModel.selectSlashCommandSuggestion,
                onTextAndCursorChange: { newText, cursorPosition in
                    viewModel.onInputTextChangeWithCursor(
                        newText: newText,
                        cursorPosition: Int32(cursorPosition)
                    )
                },
                onSend: viewModel.sendCurrentMessage,
                onAbort: viewModel.abortSession,
                onSelectThinkingVariant: viewModel.setThinkingVariant,
                onPasteImageData: addPastedImages
            )
        }
        .padding(.horizontal, 12)
        .padding(.top, 4)
        .chatGlassHostRegion(cornerRadius: 36, tint: .clear)
        .padding(.bottom, safeBottomInset)
        .background(HeightReader(height: $bottomOverlayHeight))
    }
}

private struct ChatScreenBackground: View {
    var body: some View {
        ZStack {
            Color(.systemBackground)
                .ignoresSafeArea()

            LinearGradient(
                colors: [
                    Color.accentColor.opacity(0.08),
                    Color(.systemBackground),
                    Color.orange.opacity(0.05)
                ],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            .ignoresSafeArea()

            Circle()
                .fill(Color.accentColor.opacity(0.07))
                .frame(width: 220, height: 220)
                .blur(radius: 28)
                .offset(x: 110, y: -240)

            Circle()
                .fill(Color.orange.opacity(0.05))
                .frame(width: 260, height: 260)
                .blur(radius: 36)
                .offset(x: -120, y: 280)
        }
    }
}

@MainActor
private final class ChatViewControllerStore: ObservableObject {
    let controller: ChatViewController

    init(viewModel: ChatViewModel) {
        controller = ChatViewController(
            viewModel: viewModel,
            onOpenFile: { _ in }
        )
    }
}

private struct ChatUIKitContainerView: UIViewControllerRepresentable {
    let controller: ChatViewController
    let onOpenFile: (String) -> Void
    let topInset: CGFloat
    let bottomInset: CGFloat

    func makeUIViewController(context: Context) -> ChatViewController {
        controller.setChromeInsets(top: topInset, bottom: bottomInset)
        return controller
    }

    func updateUIViewController(_ uiViewController: ChatViewController, context: Context) {
        uiViewController.onOpenFile = onOpenFile
        uiViewController.setChromeInsets(top: topInset, bottom: bottomInset)
    }
}

private struct HeightReader: View {
    @Binding var height: CGFloat

    var body: some View {
        GeometryReader { proxy in
            Color.clear
                .onAppear {
                    update(proxy.size.height)
                }
                .onChange(of: proxy.size.height) { _, newValue in
                    update(newValue)
                }
        }
    }

    private func update(_ newHeight: CGFloat) {
        guard abs(height - newHeight) > 0.5 else { return }
        DispatchQueue.main.async {
            height = newHeight
        }
    }
}
