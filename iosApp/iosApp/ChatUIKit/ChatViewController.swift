import UIKit
import PhotosUI
import UniformTypeIdentifiers
import ComposeApp

@MainActor
final class ChatViewController: UIViewController {
    var onOpenFile: (String) -> Void

    private let viewModel: ChatViewModel
    private let typingIndicatorItemId = "__typing-indicator__"

    private let uiStateBridge = KmpUiEventBridge<ChatUiState>()
    private var latestUiState: ChatUiState?

    private enum Section {
        case main
    }

    private struct MessageItem: Hashable {
        let id: String
    }

    private var messageById: [String: Message] = [:]
    private var lastMessageIds: [String] = []
    private var lastContentToken: Int = 0
    private var lastMessageTokensById: [String: Int] = [:]
    private var lastMessageLayoutTokensById: [String: Int] = [:]
    private var lastAssistantVisibilityToken: Int?
    private var lastAlwaysExpandAssistantParts: Bool?
    private var needsVisibleMessageCellRefresh: Bool = false

    private var expandedPartKeys: Set<String> = []
    private var collapsedPartKeys: Set<String> = []

    private var autoScrollPolicy = ChatAutoScrollPolicy()
    private var didInitialScroll: Bool = false
    private var isPinnedToBottom: Bool = true

    private var presentedActionSheetMessageId: String?
    private var isPresentingRevertConfirmation: Bool = false
    private var messageActionAnchorMessageId: String?
    private var messageActionAnchorRectInView: CGRect?
    private var didSelectMessageActionSheetAction: Bool = false
    private var didSelectRevertConfirmationAction: Bool = false
    private var presentedPermissionRequestId: String?

    private var dataSource: UICollectionViewDiffableDataSource<Section, MessageItem>?
    private var keyboardFrameObserver: NSObjectProtocol?

    private let headerStack = UIStackView()
    private let connectionBannerView = ChatConnectionBannerView()
    private let errorBannerView = ChatErrorBannerView()
    private let processingBar = ChatIndeterminateProgressBarView()

    private let backgroundStatusView = ChatCollectionBackgroundStatusView()

    private lazy var collectionView: UICollectionView = {
        let layout = UICollectionViewCompositionalLayout { _, _ in
            let itemSize = NSCollectionLayoutSize(
                widthDimension: .fractionalWidth(1.0),
                // 44 is too small for our bubble+insets and causes noisy Auto Layout warnings during
                // the initial estimated-size pass.
                heightDimension: .estimated(160)
            )
            let item = NSCollectionLayoutItem(layoutSize: itemSize)

            let groupSize = NSCollectionLayoutSize(
                widthDimension: .fractionalWidth(1.0),
                heightDimension: .estimated(160)
            )
            let group = NSCollectionLayoutGroup.vertical(layoutSize: groupSize, subitems: [item])
            let section = NSCollectionLayoutSection(group: group)
            section.contentInsets = NSDirectionalEdgeInsets(top: 12, leading: 0, bottom: 12, trailing: 0)
            return section
        }

        let view = UICollectionView(frame: .zero, collectionViewLayout: layout)
        view.backgroundColor = .clear
        view.alwaysBounceVertical = true
        view.keyboardDismissMode = .interactive
        return view
    }()

    private let bottomStack = UIStackView()
    private let attachmentErrorView = ChatAttachmentErrorSnackbarView()
    private var attachmentErrorDismissTask: Task<Void, Never>?
    private var lastAttachmentErrorKey: String?

    private let composerContainer = UIView()
    private let composerView = ChatComposerView()
    private let scrollToBottomButton = UIButton(type: .system)

    init(
        viewModel: ChatViewModel,
        onOpenFile: @escaping (String) -> Void
    ) {
        self.viewModel = viewModel
        self.onOpenFile = onOpenFile
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()

        edgesForExtendedLayout = []
        view.backgroundColor = .systemBackground

        collectionView.register(ChatMessageCell.self, forCellWithReuseIdentifier: ChatMessageCell.reuseIdentifier)
        collectionView.register(ChatTypingIndicatorRowCell.self, forCellWithReuseIdentifier: ChatTypingIndicatorRowCell.reuseIdentifier)
        collectionView.delegate = self

        headerStack.axis = .vertical
        headerStack.spacing = 0

        connectionBannerView.isHidden = true
        errorBannerView.isHidden = true

        processingBar.barColor = .systemBlue
        processingBar.isHidden = true

        headerStack.addArrangedSubview(connectionBannerView)
        headerStack.addArrangedSubview(errorBannerView)
        headerStack.addArrangedSubview(processingBar)

        connectionBannerView.translatesAutoresizingMaskIntoConstraints = false
        errorBannerView.translatesAutoresizingMaskIntoConstraints = false
        processingBar.translatesAutoresizingMaskIntoConstraints = false

        collectionView.backgroundView = backgroundStatusView

        attachmentErrorView.isHidden = true
        attachmentErrorView.onDismiss = { [weak self] in
            self?.viewModel.dismissAttachmentError()
        }

        composerContainer.backgroundColor = .clear
        composerContainer.addSubview(composerView)
        composerView.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            composerView.topAnchor.constraint(equalTo: composerContainer.topAnchor, constant: 8),
            composerView.leadingAnchor.constraint(equalTo: composerContainer.leadingAnchor, constant: 12),
            composerView.trailingAnchor.constraint(equalTo: composerContainer.trailingAnchor, constant: -12),
            composerView.bottomAnchor.constraint(equalTo: composerContainer.bottomAnchor, constant: -8),
        ])

        bottomStack.axis = .vertical
        bottomStack.spacing = 0
        bottomStack.addArrangedSubview(attachmentErrorView)
        bottomStack.addArrangedSubview(composerContainer)

        attachmentErrorView.translatesAutoresizingMaskIntoConstraints = false
        composerContainer.translatesAutoresizingMaskIntoConstraints = false

        scrollToBottomButton.setImage(UIImage(systemName: "arrow.down"), for: .normal)
        scrollToBottomButton.tintColor = .label
        scrollToBottomButton.backgroundColor = UIColor.secondarySystemBackground.withAlphaComponent(0.9)
        scrollToBottomButton.layer.cornerRadius = 20
        scrollToBottomButton.layer.cornerCurve = .continuous
        scrollToBottomButton.clipsToBounds = true
        scrollToBottomButton.isHidden = true
        scrollToBottomButton.addTarget(self, action: #selector(didTapScrollToBottom), for: .touchUpInside)

        composerView.onPickPhotos = { [weak self] in
            self?.presentPhotoPicker()
        }
        composerView.onPickFiles = { [weak self] in
            self?.presentDocumentPicker()
        }
        composerView.onAddFromClipboard = { [weak self] in
            self?.viewModel.addFromClipboard()
        }
        composerView.onRemoveAttachment = { [weak self] attachment in
            self?.viewModel.removeAttachment(attachment: attachment)
        }
        composerView.onSelectMentionSuggestion = { [weak self] entry in
            self?.viewModel.selectMentionSuggestion(entry: entry)
        }
        composerView.onSelectSlashCommandSuggestion = { [weak self] command in
            self?.viewModel.selectSlashCommandSuggestion(command: command)
        }
        composerView.onTextAndCursorChange = { [weak self] newText, cursorPosition in
            self?.viewModel.onInputTextChangeWithCursor(
                newText: newText,
                cursorPosition: Int32(cursorPosition)
            )
        }
        composerView.onSend = { [weak self] in
            self?.viewModel.sendCurrentMessage()
        }
        composerView.onAbort = { [weak self] in
            self?.viewModel.abortSession()
        }
        composerView.onSelectThinkingVariant = { [weak self] variant in
            self?.viewModel.setThinkingVariant(variant: variant)
        }

        view.addSubview(headerStack)
        view.addSubview(collectionView)
        view.addSubview(bottomStack)
        view.addSubview(scrollToBottomButton)

        headerStack.translatesAutoresizingMaskIntoConstraints = false
        collectionView.translatesAutoresizingMaskIntoConstraints = false
        bottomStack.translatesAutoresizingMaskIntoConstraints = false
        scrollToBottomButton.translatesAutoresizingMaskIntoConstraints = false

        let bottomToSafeArea = bottomStack.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor)
        bottomToSafeArea.priority = .defaultHigh

        NSLayoutConstraint.activate([
            headerStack.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            headerStack.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            headerStack.trailingAnchor.constraint(equalTo: view.trailingAnchor),

            bottomStack.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            bottomStack.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            bottomToSafeArea,
            bottomStack.bottomAnchor.constraint(lessThanOrEqualTo: view.keyboardLayoutGuide.topAnchor),

            collectionView.topAnchor.constraint(equalTo: headerStack.bottomAnchor),
            collectionView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            collectionView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            collectionView.bottomAnchor.constraint(equalTo: bottomStack.topAnchor),

            scrollToBottomButton.widthAnchor.constraint(equalToConstant: 40),
            scrollToBottomButton.heightAnchor.constraint(equalToConstant: 40),
            scrollToBottomButton.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -16),
            scrollToBottomButton.bottomAnchor.constraint(equalTo: bottomStack.topAnchor, constant: -16),
        ])

        configureDataSource()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)

        uiStateBridge.start(flow: viewModel.uiState) { [weak self] state in
            self?.render(state: state)
        }

        if keyboardFrameObserver == nil {
            keyboardFrameObserver = NotificationCenter.default.addObserver(
                forName: UIResponder.keyboardWillChangeFrameNotification,
                object: nil,
                queue: .main
            ) { [weak self] _ in
                self?.handleKeyboardFrameChange()
            }
        }
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        refreshVisibleMessageCellsIfNeeded()
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        uiStateBridge.stop()
        processingBar.stopAnimating()
        attachmentErrorDismissTask?.cancel()
        attachmentErrorDismissTask = nil
        if let observer = keyboardFrameObserver {
            NotificationCenter.default.removeObserver(observer)
            keyboardFrameObserver = nil
        }
    }

    private func configureDataSource() {
        dataSource = UICollectionViewDiffableDataSource<Section, MessageItem>(
            collectionView: collectionView
        ) { [weak self] collectionView, indexPath, item in
            guard let self else { return nil }
            if item.id == self.typingIndicatorItemId {
                return collectionView.dequeueReusableCell(
                    withReuseIdentifier: ChatTypingIndicatorRowCell.reuseIdentifier,
                    for: indexPath
                )
            }
            guard let cell = collectionView.dequeueReusableCell(
                withReuseIdentifier: ChatMessageCell.reuseIdentifier,
                for: indexPath
            ) as? ChatMessageCell else {
                return nil
            }
            self.configureMessageCell(cell, item: item)
            return cell
        }
    }

    private func configureMessageCell(_ cell: ChatMessageCell, item: MessageItem) {
        let message = messageById[item.id]
        let alwaysExpand = latestUiState?.alwaysExpandAssistantParts ?? false
        cell.configure(
            message: message,
            uiState: latestUiState,
            expandedPartKeys: expandedPartKeys,
            collapsedPartKeys: collapsedPartKeys,
            alwaysExpandAssistantParts: alwaysExpand,
            onToggleExpandablePart: { [weak self] key, isExpanded in
                guard let self else { return }
                if alwaysExpand {
                    if isExpanded {
                        self.collapsedPartKeys.remove(key)
                    } else {
                        self.collapsedPartKeys.insert(key)
                    }
                } else {
                    if isExpanded {
                        self.expandedPartKeys.insert(key)
                    } else {
                        self.expandedPartKeys.remove(key)
                    }
                }
            },
            onShowActions: { [weak self] sourceView in
                guard let self, let message else { return }
                self.messageActionAnchorMessageId = message.id
                self.messageActionAnchorRectInView = sourceView.convert(sourceView.bounds, to: self.view)
                self.viewModel.showMessageActions(message: message)
            },
            onOpenFile: { [weak self] path in
                self?.onOpenFile(path)
            }
        )
    }

    private func render(state: ChatUiState) {
        latestUiState = state

        let showTypingIndicator = TypingIndicatorKt.shouldShowTypingIndicator(state: state)
        renderHeader(state: state, showTypingIndicator: showTypingIndicator)
        composerView.render(uiState: state)
        renderAttachmentError(state.attachmentError)

        handleMessageActions(state: state)
        handleRevertConfirmation(state: state)
        handlePermissionPrompt(state: state)

        let visibleMessages = filteredVisibleMessages(state: state)
        if state.isLoading, visibleMessages.isEmpty, !showTypingIndicator {
            backgroundStatusView.setMode(.loading)
        } else if visibleMessages.isEmpty, !showTypingIndicator {
            backgroundStatusView.setMode(.empty)
        } else {
            backgroundStatusView.setMode(.hidden)
        }

        var newMessageIds = visibleMessages.map(\.id)
        if showTypingIndicator {
            newMessageIds.append(typingIndicatorItemId)
        }
        messageById = Dictionary(uniqueKeysWithValues: state.messages.map { ($0.id, $0) })
        let (contentToken, messageTokensById) = computeContentToken(
            messageIds: newMessageIds,
            state: state
        )
        let messageLayoutTokensById = computeMessageLayoutTokens(messageIds: newMessageIds)
        let updateKind = classifyUpdateKind(messageIds: newMessageIds, contentToken: contentToken)
        let visibilityToken = assistantVisibilityToken(state.assistantResponsePartVisibility)
        let assistantVisibilityChanged = lastAssistantVisibilityToken != nil && lastAssistantVisibilityToken != visibilityToken
        let alwaysExpandChanged = lastAlwaysExpandAssistantParts != nil &&
            lastAlwaysExpandAssistantParts != state.alwaysExpandAssistantParts
        if alwaysExpandChanged {
            if state.alwaysExpandAssistantParts {
                collapsedPartKeys.removeAll()
            } else {
                expandedPartKeys.removeAll()
            }
        }
        if assistantVisibilityChanged || alwaysExpandChanged {
            needsVisibleMessageCellRefresh = true
        }
        let decision = autoScrollPolicy.decide(
            updateKind: updateKind,
            isPinnedToBottom: isPinnedToBottom,
            didInitialScroll: didInitialScroll
        )

        // Session switches can briefly render an empty list before messages load. Avoid "consuming"
        // the initial scroll on an empty render so we still scroll to the latest message once the
        // real message list arrives.
        if decision.didConsumeInitialScroll, !newMessageIds.isEmpty {
            didInitialScroll = true
        }

        let idsToReconfigure = idsToReconfigure(
            messageIds: newMessageIds,
            state: state,
            messageTokensById: messageTokensById,
            updateKind: updateKind
        )
        let shouldInvalidateLayoutAfterUpdate: Bool = {
            switch updateKind {
            case .messagesAppended:
                return true
            case .messageContentUpdated:
                let streamingId = state.streamingMessageId
                if idsToReconfigure.contains(where: { $0 != streamingId }) {
                    return true
                }

                // If the only updates are to the currently streaming message, we usually avoid
                // invalidating layout to keep scrolling smooth. However, when the *structure* of the
                // message changes (e.g., a new Thinking/Tool block appears), we must invalidate so
                // the cell can grow and reveal the new content.
                guard let streamingId, idsToReconfigure.contains(streamingId) else { return false }
                return lastMessageLayoutTokensById[streamingId] != messageLayoutTokensById[streamingId]
            default:
                return false
            }
        }()
        applyMessages(
            messageIds: newMessageIds,
            updateKind: updateKind,
            idsToReconfigure: idsToReconfigure,
            forceReconfigureAllItems: assistantVisibilityChanged || alwaysExpandChanged
        ) { [weak self] in
            guard let self else { return }
            if shouldInvalidateLayoutAfterUpdate {
                UIView.performWithoutAnimation {
                    self.collectionView.collectionViewLayout.invalidateLayout()
                    self.collectionView.performBatchUpdates(nil)
                }
            }
            if decision.shouldScrollToBottom {
                self.scrollToBottom(animated: decision.animateScroll)
            }
            self.scrollToBottomButton.isHidden = !decision.shouldShowScrollToBottomButton
            self.refreshVisibleMessageCellsIfNeeded()
        }

        lastContentToken = contentToken
        lastMessageTokensById = messageTokensById
        lastMessageLayoutTokensById = messageLayoutTokensById
        lastAssistantVisibilityToken = visibilityToken
        lastAlwaysExpandAssistantParts = state.alwaysExpandAssistantParts
    }

    private func filteredVisibleMessages(state: ChatUiState) -> [Message] {
        let assistantVisibility = state.assistantResponsePartVisibility

        return state.messages.filter { message in
            if message is UserMessage { return true }

            if let assistant = message as? AssistantMessage, assistant.error != nil {
                return true
            }

            let visibleParts = MessagePartVisibilityKt.filterVisibleParts(
                parts: message.parts,
                visibility: assistantVisibility
            )
            return !visibleParts.isEmpty
        }
    }

    private func assistantVisibilityToken(_ visibility: AssistantResponsePartVisibility) -> Int {
        var token = 0
        if visibility.showReasoning { token |= 1 << 0 }
        if visibility.showTools { token |= 1 << 1 }
        if visibility.showPatches { token |= 1 << 2 }
        if visibility.showAgents { token |= 1 << 3 }
        if visibility.showRetries { token |= 1 << 4 }
        if visibility.showCompactions { token |= 1 << 5 }
        if visibility.showUnknowns { token |= 1 << 6 }
        return token
    }

    private func refreshVisibleMessageCellsIfNeeded() {
        guard needsVisibleMessageCellRefresh else { return }
        guard isViewLoaded, view.window != nil else { return }
        guard let dataSource else { return }
        let indexPaths = collectionView.indexPathsForVisibleItems
        guard !indexPaths.isEmpty else { return }

        UIView.performWithoutAnimation {
            for indexPath in indexPaths {
                guard let item = dataSource.itemIdentifier(for: indexPath) else { continue }
                guard let cell = collectionView.cellForItem(at: indexPath) as? ChatMessageCell else { continue }
                configureMessageCell(cell, item: item)
            }
            collectionView.collectionViewLayout.invalidateLayout()
            collectionView.layoutIfNeeded()
        }
        needsVisibleMessageCellRefresh = false
    }

    private func renderHeader(state: ChatUiState, showTypingIndicator: Bool) {
        let isReconnecting = state.connectionState.name.uppercased() == "RECONNECTING"
        connectionBannerView.isHidden = !isReconnecting

        if let error = state.error {
            errorBannerView.isHidden = false
            let shouldShowRevert =
                state.lastGoodMessageId != nil
                && (error is ChatError.SessionCorrupted || error is ChatError.SendFailed)
            errorBannerView.configure(
                message: error.message ?? "An error occurred.",
                showRevert: shouldShowRevert,
                onDismiss: { [weak self] in self?.viewModel.dismissError() },
                onRetry: { [weak self] in self?.viewModel.retry() },
                onRevert: { [weak self] in self?.viewModel.revertToLastGood() }
            )
        } else {
            errorBannerView.isHidden = true
        }

        // Only show the processing bar when the current visibility preset does NOT show the
        // in-thread typing indicator (e.g., ALL/CUSTOM).
        let isProcessing = state.sessionStatus.name.uppercased() == "PROCESSING"
        let shouldShowProcessingBar = isProcessing && !showTypingIndicator
        processingBar.isHidden = !shouldShowProcessingBar
        if shouldShowProcessingBar {
            processingBar.startAnimating()
        } else {
            processingBar.stopAnimating()
        }
    }

    private func renderAttachmentError(_ error: AttachmentError?) {
        guard let error else {
            attachmentErrorView.isHidden = true
            lastAttachmentErrorKey = nil
            attachmentErrorDismissTask?.cancel()
            attachmentErrorDismissTask = nil
            return
        }

        attachmentErrorView.isHidden = false
        attachmentErrorView.message = ChatAttachmentErrorPresentation.message(error)

        let key = ChatAttachmentErrorPresentation.key(error)
        guard key != lastAttachmentErrorKey else { return }
        lastAttachmentErrorKey = key

        attachmentErrorDismissTask?.cancel()
        attachmentErrorDismissTask = Task { [weak self] in
            do {
                try await Task.sleep(nanoseconds: 2_750_000_000)
            } catch {
                return
            }
            if Task.isCancelled { return }
            await MainActor.run {
                self?.viewModel.dismissAttachmentError()
            }
        }
    }

    private func handleMessageActions(state: ChatUiState) {
        guard let message = state.selectedMessageForAction else {
            presentedActionSheetMessageId = nil
            return
        }

        if presentedActionSheetMessageId == message.id { return }
        presentedActionSheetMessageId = message.id

        presentActionSheet(for: message)
    }

    private func handlePermissionPrompt(state: ChatUiState) {
        guard let request = state.pendingPermission else {
            presentedPermissionRequestId = nil
            return
        }

        if presentedPermissionRequestId == request.requestId { return }

        // Don't mark the request as "presented" until we actually present the alert. UIKit may refuse
        // presentation if another modal is already displayed (action sheet, confirmation, etc.).
        guard isViewLoaded, view.window != nil else { return }
        guard presentedViewController == nil else { return }

        var messageLines: [String] = []
        if !request.permission.isEmpty {
            messageLines.append("Tool: \(request.permission)")
        }
        if let callId = request.toolCallId, !callId.isEmpty {
            messageLines.append("Call: \(callId)")
        }
        if !request.patterns.isEmpty {
            messageLines.append("")
            messageLines.append("Requested:")
            messageLines.append(contentsOf: request.patterns.prefix(8).map { "• \($0)" })
            if request.patterns.count > 8 {
                messageLines.append("…and \(request.patterns.count - 8) more")
            }
        }

        let alert = UIAlertController(
            title: "Permission required",
            message: messageLines.joined(separator: "\n"),
            preferredStyle: .alert
        )

        alert.addAction(UIAlertAction(title: "Once", style: .default) { [weak self] _ in
            self?.viewModel.replyToPermissionRequest(requestId: request.requestId, reply: "once", message: nil)
        })
        alert.addAction(UIAlertAction(title: "Always", style: .default) { [weak self] _ in
            self?.viewModel.replyToPermissionRequest(requestId: request.requestId, reply: "always", message: nil)
        })
        alert.addAction(UIAlertAction(title: "Reject", style: .destructive) { [weak self] _ in
            self?.viewModel.replyToPermissionRequest(requestId: request.requestId, reply: "reject", message: nil)
        })

        present(alert, animated: true) { [weak self] in
            self?.presentedPermissionRequestId = request.requestId
        }
    }

    private func presentActionSheet(for message: Message) {
        let sheet = UIAlertController(title: "Message actions", message: nil, preferredStyle: .actionSheet)

        sheet.addAction(UIAlertAction(title: "Revert", style: .destructive) { [weak self] _ in
            self?.didSelectMessageActionSheetAction = true
            self?.viewModel.showRevertConfirmation()
        })
        sheet.addAction(UIAlertAction(title: "Fork from here", style: .default) { [weak self] _ in
            self?.didSelectMessageActionSheetAction = true
            self?.viewModel.forkFromMessage()
        })
        sheet.addAction(UIAlertAction(title: "Copy message", style: .default) { [weak self] _ in
            guard let self else { return }
            didSelectMessageActionSheetAction = true
            let text = self.viewModel.getMessageTextForCopy()
            if let text, !text.isEmpty {
                UIPasteboard.general.string = text
            }
            self.viewModel.dismissMessageAction()
        })
        sheet.addAction(UIAlertAction(title: "Cancel", style: .cancel) { [weak self] _ in
            self?.didSelectMessageActionSheetAction = true
            self?.viewModel.dismissMessageAction()
        })

        if let popover = sheet.popoverPresentationController {
            if messageActionAnchorMessageId == message.id, let rect = messageActionAnchorRectInView {
                popover.sourceView = view
                popover.sourceRect = rect
                popover.permittedArrowDirections = [.up, .down]
            } else {
                popover.sourceView = view
                popover.sourceRect = CGRect(x: view.bounds.midX, y: view.bounds.midY, width: 0, height: 0)
                popover.permittedArrowDirections = []
            }
        }

        sheet.presentationController?.delegate = self
        present(sheet, animated: true)
    }

    private func handleRevertConfirmation(state: ChatUiState) {
        if !state.showRevertConfirmation {
            isPresentingRevertConfirmation = false
            return
        }
        if isPresentingRevertConfirmation { return }
        isPresentingRevertConfirmation = true

        let count = viewModel.getMessagesAfterSelectedCount()
        let totalReverted = count + 1
        let message = "This will revert \(totalReverted) message\(totalReverted == 1 ? "" : "s"), including the selected one."

        let alert = UIAlertController(title: "Revert to this point?", message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel) { [weak self] _ in
            self?.didSelectRevertConfirmationAction = true
            self?.viewModel.cancelRevert()
        })
        alert.addAction(UIAlertAction(title: "Revert", style: .destructive) { [weak self] _ in
            self?.didSelectRevertConfirmationAction = true
            self?.viewModel.confirmRevert()
        })

        present(alert, animated: true)
    }

    private func applyMessages(
        messageIds: [String],
        updateKind: ChatAutoScrollPolicy.UpdateKind,
        idsToReconfigure: [String] = [],
        forceReconfigureAllItems: Bool = false,
        completion: @escaping () -> Void
    ) {
        guard let dataSource else { return }

        if messageIds == lastMessageIds {
            if forceReconfigureAllItems {
                var snapshot = dataSource.snapshot()
                let items = messageIds.map { MessageItem(id: $0) }
                if !items.isEmpty {
                    snapshot.reloadItems(items)
                    dataSource.apply(snapshot, animatingDifferences: false, completion: completion)
                    return
                }
            }

            var snapshot = dataSource.snapshot()
            let itemsToReconfigure = Array(Set(idsToReconfigure)).map { MessageItem(id: $0) }
            if !itemsToReconfigure.isEmpty {
                snapshot.reloadItems(itemsToReconfigure)
                dataSource.apply(snapshot, animatingDifferences: false, completion: completion)
                return
            }
            completion()
            return
        }

        var snapshot = NSDiffableDataSourceSnapshot<Section, MessageItem>()
        snapshot.appendSections([.main])
        snapshot.appendItems(messageIds.map { MessageItem(id: $0) }, toSection: .main)
        if forceReconfigureAllItems {
            snapshot.reloadItems(snapshot.itemIdentifiers)
        } else {
            let itemIds = Set(messageIds)
            let itemsToReconfigure = Array(Set(idsToReconfigure))
                .filter { itemIds.contains($0) }
                .map { MessageItem(id: $0) }
            if !itemsToReconfigure.isEmpty {
                snapshot.reloadItems(itemsToReconfigure)
            }
        }
        lastMessageIds = messageIds
        dataSource.apply(snapshot, animatingDifferences: false, completion: completion)
    }

    private func classifyUpdateKind(
        messageIds: [String],
        contentToken: Int
    ) -> ChatAutoScrollPolicy.UpdateKind {
        if lastMessageIds.isEmpty && !messageIds.isEmpty {
            return .initialLoad
        }

        if isPrefixAppend(old: lastMessageIds, new: messageIds) {
            return .messagesAppended
        }

        if messageIds == lastMessageIds {
            if contentToken != lastContentToken {
                return .messageContentUpdated
            }
            return .layoutOnly
        }

        // If the only change is the typing indicator disappearing, treat it as a benign layout-only
        // change so we don't reset scroll position for users who have scrolled up mid-response.
        if lastMessageIds.last == typingIndicatorItemId,
           lastMessageIds.count == messageIds.count + 1,
           Array(lastMessageIds.dropLast()) == messageIds {
            return .layoutOnly
        }

        didInitialScroll = false
        isPinnedToBottom = true
        scrollToBottomButton.isHidden = true
        return .initialLoad
    }

    private func isPrefixAppend(old: [String], new: [String]) -> Bool {
        guard new.count > old.count else { return false }
        return Array(new.prefix(old.count)) == old
    }

    private func computeContentToken(
        messageIds: [String],
        state: ChatUiState
    ) -> (token: Int, messageTokensById: [String: Int]) {
        var token = messageIds.count
        token &+= state.streamingMessageId?.count ?? 0
        token &+= assistantVisibilityToken(state.assistantResponsePartVisibility)

        var messageTokensById: [String: Int] = [:]
        messageTokensById.reserveCapacity(messageIds.count)
        for id in messageIds {
            guard let message = messageById[id] else { continue }
            let messageToken = messageContentToken(message)
            messageTokensById[message.id] = messageToken
            token &+= messageToken
        }

        return (token, messageTokensById)
    }

    private func computeMessageLayoutTokens(messageIds: [String]) -> [String: Int] {
        var messageTokensById: [String: Int] = [:]
        messageTokensById.reserveCapacity(messageIds.count)
        for id in messageIds {
            guard let message = messageById[id] else { continue }
            messageTokensById[message.id] = messageLayoutToken(message)
        }
        return messageTokensById
    }

    private func messageContentToken(_ message: Message) -> Int {
        func combineString(_ hasher: inout Hasher, _ value: String?) {
            let str = value ?? ""
            hasher.combine(str.utf16.count)
            if str.utf16.count <= 256 {
                hasher.combine(str)
            } else {
                hasher.combine(String(str.prefix(128)))
                hasher.combine(String(str.suffix(128)))
            }
        }

        var hasher = Hasher()
        combineString(&hasher, message.id)
        hasher.combine(message.parts.count)

        for part in message.parts {
            if let text = part as? TextPart {
                hasher.combine(1)
                combineString(&hasher, text.id)
                hasher.combine(text.synthetic)
                combineString(&hasher, text.text)
                continue
            }
            if let reasoning = part as? ReasoningPart {
                hasher.combine(2)
                combineString(&hasher, reasoning.id)
                combineString(&hasher, reasoning.text)
                continue
            }
            if let tool = part as? ToolPart {
                hasher.combine(3)
                combineString(&hasher, tool.id)
                combineString(&hasher, tool.callId)
                combineString(&hasher, tool.tool)
                combineString(&hasher, tool.state.name)
                combineString(&hasher, tool.input)
                combineString(&hasher, tool.output)
                combineString(&hasher, tool.error)
                continue
            }
            if let file = part as? FilePart {
                hasher.combine(4)
                combineString(&hasher, file.id)
                combineString(&hasher, file.mime)
                combineString(&hasher, file.filename)
                combineString(&hasher, file.url)
                continue
            }
            if let patch = part as? PatchPart {
                hasher.combine(5)
                combineString(&hasher, patch.id)
                combineString(&hasher, patch.hash_)
                for file in patch.files {
                    combineString(&hasher, file)
                }
                continue
            }
            if let agent = part as? AgentPart {
                hasher.combine(6)
                combineString(&hasher, agent.id)
                combineString(&hasher, agent.name)
                continue
            }
            if let retry = part as? RetryPart {
                hasher.combine(7)
                combineString(&hasher, retry.id)
                hasher.combine(retry.attempt)
                combineString(&hasher, retry.error?.message)
                continue
            }
            if let compaction = part as? CompactionPart {
                hasher.combine(8)
                combineString(&hasher, compaction.id)
                hasher.combine(compaction.auto)
                continue
            }
            if let unknown = part as? UnknownPart {
                hasher.combine(9)
                combineString(&hasher, unknown.id)
                combineString(&hasher, unknown.type)
                combineString(&hasher, unknown.rawData)
                continue
            }
            if part is StepStartPart {
                hasher.combine(10)
                continue
            }
            if part is StepFinishPart {
                hasher.combine(11)
                continue
            }
            if part is SnapshotPart {
                hasher.combine(12)
                continue
            }

            hasher.combine(999)
            hasher.combine(part.hash)
        }

        return hasher.finalize()
    }

    private func messageLayoutToken(_ message: Message) -> Int {
        var hasher = Hasher()
        var renderablePartCount = 0
        for part in message.parts {
            if part is StepStartPart || part is StepFinishPart || part is SnapshotPart { continue }
            renderablePartCount += 1
        }
        hasher.combine(renderablePartCount)

        for part in message.parts {
            // Step markers and snapshots are never rendered; they shouldn't affect layout invalidation.
            if part is StepStartPart || part is StepFinishPart || part is SnapshotPart {
                continue
            }
            if let text = part as? TextPart {
                hasher.combine(1)
                hasher.combine(text.synthetic)
                hasher.combine(text.text.isEmpty)
                continue
            }
            if let reasoning = part as? ReasoningPart {
                hasher.combine(2)
                hasher.combine(reasoning.text.isEmpty)
                continue
            }
            if let tool = part as? ToolPart {
                hasher.combine(3)
                hasher.combine(tool.tool)
                hasher.combine(tool.state.name)
                hasher.combine((tool.output ?? "").isEmpty == false)
                hasher.combine((tool.error ?? "").isEmpty == false)
                continue
            }
            if part is FilePart {
                hasher.combine(4)
                continue
            }
            if let patch = part as? PatchPart {
                hasher.combine(5)
                hasher.combine(patch.files.count)
                continue
            }
            if part is AgentPart {
                hasher.combine(6)
                continue
            }
            if let retry = part as? RetryPart {
                hasher.combine(7)
                hasher.combine(retry.attempt)
                hasher.combine((retry.error?.message ?? "").isEmpty == false)
                continue
            }
            if part is CompactionPart {
                hasher.combine(8)
                continue
            }
            if let unknown = part as? UnknownPart {
                hasher.combine(9)
                hasher.combine(unknown.type)
                hasher.combine(unknown.rawData.isEmpty)
                continue
            }

            hasher.combine(999)
            hasher.combine(part.hash)
        }

        return hasher.finalize()
    }

    private func idsToReconfigure(
        messageIds: [String],
        state: ChatUiState,
        messageTokensById: [String: Int],
        updateKind: ChatAutoScrollPolicy.UpdateKind
    ) -> [String] {
        var ids = Set<String>()

        // Reconfigure any messages whose content token changed since last render.
        for id in messageIds {
            guard let newToken = messageTokensById[id] else { continue }
            guard let oldToken = lastMessageTokensById[id] else { continue }
            if newToken != oldToken {
                ids.insert(id)
            }
        }

        // Defensive: if we know "something" changed but couldn't identify it, refresh the last message.
        if ids.isEmpty, updateKind == .messageContentUpdated, let last = messageIds.last {
            ids.insert(last)
        }

        return Array(ids)
    }

    @objc private func didTapScrollToBottom() {
        scrollToBottom(animated: true)
    }

    private func scrollToBottom(animated: Bool) {
        guard collectionView.numberOfSections > 0 else { return }
        let count = collectionView.numberOfItems(inSection: 0)
        guard count > 0 else { return }
        let indexPath = IndexPath(item: count - 1, section: 0)
        collectionView.layoutIfNeeded()
        collectionView.scrollToItem(at: indexPath, at: .bottom, animated: animated)
        isPinnedToBottom = true
        scrollToBottomButton.isHidden = true
    }

    private func handleKeyboardFrameChange() {
        guard isPinnedToBottom else { return }
        if collectionView.isDragging || collectionView.isDecelerating { return }
        DispatchQueue.main.async { [weak self] in
            self?.scrollToBottom(animated: false)
        }
    }
}

private final class ChatCollectionBackgroundStatusView: UIView {
    enum Mode {
        case loading
        case empty
        case hidden
    }

    private let card = UIVisualEffectView(effect: UIBlurEffect(style: .systemUltraThinMaterial))
    private let stack = UIStackView()
    private let iconRow = UIView()
    private let iconView = UIImageView()
    private let spinnerRow = UIView()
    private let spinner = UIActivityIndicatorView(style: .medium)
    private let titleLabel = UILabel()
    private let subtitleLabel = UILabel()

    override init(frame: CGRect) {
        super.init(frame: frame)
        setUp()
        setMode(.loading)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    private func setUp() {
        backgroundColor = .clear

        card.layer.cornerRadius = 18
        card.layer.cornerCurve = .continuous
        card.clipsToBounds = true

        iconView.image = UIImage(systemName: "sparkles")
        iconView.tintColor = .secondaryLabel
        iconView.contentMode = .scaleAspectFit

        titleLabel.font = .preferredFont(forTextStyle: .headline)
        titleLabel.textColor = .label
        titleLabel.numberOfLines = 0
        titleLabel.textAlignment = .center

        subtitleLabel.font = .preferredFont(forTextStyle: .subheadline)
        subtitleLabel.textColor = .secondaryLabel
        subtitleLabel.numberOfLines = 0
        subtitleLabel.textAlignment = .center

        stack.axis = .vertical
        stack.spacing = 10
        stack.alignment = .fill
        stack.layoutMargins = UIEdgeInsets(top: 18, left: 18, bottom: 18, right: 18)
        stack.isLayoutMarginsRelativeArrangement = true

        iconRow.backgroundColor = .clear
        iconRow.addSubview(iconView)
        spinnerRow.backgroundColor = .clear
        spinnerRow.addSubview(spinner)

        stack.addArrangedSubview(iconRow)
        stack.addArrangedSubview(spinnerRow)
        stack.addArrangedSubview(titleLabel)
        stack.addArrangedSubview(subtitleLabel)

        addSubview(card)
        card.contentView.addSubview(stack)

        card.translatesAutoresizingMaskIntoConstraints = false
        stack.translatesAutoresizingMaskIntoConstraints = false
        iconRow.translatesAutoresizingMaskIntoConstraints = false
        iconView.translatesAutoresizingMaskIntoConstraints = false
        spinnerRow.translatesAutoresizingMaskIntoConstraints = false
        spinner.translatesAutoresizingMaskIntoConstraints = false

        let leading = card.leadingAnchor.constraint(greaterThanOrEqualTo: leadingAnchor, constant: 24)
        leading.priority = .defaultHigh
        let trailing = card.trailingAnchor.constraint(lessThanOrEqualTo: trailingAnchor, constant: -24)
        trailing.priority = .defaultHigh

        NSLayoutConstraint.activate([
            card.centerXAnchor.constraint(equalTo: centerXAnchor),
            card.centerYAnchor.constraint(equalTo: centerYAnchor),
            leading,
            trailing,
            card.widthAnchor.constraint(lessThanOrEqualToConstant: 420),

            stack.topAnchor.constraint(equalTo: card.contentView.topAnchor),
            stack.leadingAnchor.constraint(equalTo: card.contentView.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: card.contentView.trailingAnchor),
            stack.bottomAnchor.constraint(equalTo: card.contentView.bottomAnchor),

            iconRow.heightAnchor.constraint(equalToConstant: 28),
            iconView.centerXAnchor.constraint(equalTo: iconRow.centerXAnchor),
            iconView.centerYAnchor.constraint(equalTo: iconRow.centerYAnchor),
            iconView.heightAnchor.constraint(equalToConstant: 26),
            iconView.widthAnchor.constraint(equalToConstant: 26),

            spinnerRow.heightAnchor.constraint(equalToConstant: 28),
            spinner.centerXAnchor.constraint(equalTo: spinnerRow.centerXAnchor),
            spinner.centerYAnchor.constraint(equalTo: spinnerRow.centerYAnchor),
        ])
    }

    func setMode(_ mode: Mode) {
        switch mode {
        case .loading:
            isHidden = false
            iconView.isHidden = false
            spinner.isHidden = false
            spinner.startAnimating()
            titleLabel.text = "Loading…"
            subtitleLabel.text = "Syncing your conversation."
            subtitleLabel.isHidden = false

        case .empty:
            isHidden = false
            iconView.isHidden = false
            spinner.stopAnimating()
            spinner.isHidden = true
            titleLabel.text = "Start a conversation"
            subtitleLabel.text = "Ask OpenCode anything — or paste a link to discuss."
            subtitleLabel.isHidden = false

        case .hidden:
            spinner.stopAnimating()
            isHidden = true
        }
    }
}

extension ChatViewController: UICollectionViewDelegate {
    func scrollViewDidScroll(_ scrollView: UIScrollView) {
        let metrics = ChatAutoScrollPolicy.ScrollMetrics(
            contentOffsetY: Double(scrollView.contentOffset.y),
            viewportHeight: Double(scrollView.bounds.height),
            contentHeight: Double(scrollView.contentSize.height)
        )
        let pinned = autoScrollPolicy.isPinnedToBottom(metrics: metrics)
        if pinned != isPinnedToBottom {
            isPinnedToBottom = pinned
        }

        if pinned {
            scrollToBottomButton.isHidden = true
        } else if collectionView.numberOfSections > 0, collectionView.numberOfItems(inSection: 0) > 0 {
            scrollToBottomButton.isHidden = false
        }
    }
}

// MARK: - Attachments (Photos + Files)

extension ChatViewController: PHPickerViewControllerDelegate, UIDocumentPickerDelegate {
    private func remainingAttachmentSlots() -> Int {
        let maxFiles = Int(AttachmentLimits.shared.MAX_FILES_PER_MESSAGE)
        let current = latestUiState?.pendingAttachments.count ?? 0
        return max(0, maxFiles - current)
    }

    private func presentPhotoPicker() {
        let remaining = remainingAttachmentSlots()
        guard remaining > 0 else { return }

        var config = PHPickerConfiguration(photoLibrary: .shared())
        config.selectionLimit = remaining
        config.filter = .images

        let picker = PHPickerViewController(configuration: config)
        picker.delegate = self
        present(picker, animated: true)
    }

    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: true)
        guard !results.isEmpty else { return }
        Task { [weak self] in
            await self?.addPhotos(results)
        }
    }

    private func addPhotos(_ results: [PHPickerResult]) async {
        let remaining = remainingAttachmentSlots()
        guard remaining > 0 else { return }

        for result in results.prefix(remaining) {
            let provider = result.itemProvider
            guard provider.canLoadObject(ofClass: UIImage.self) else { continue }

            let image: UIImage
            do {
                image = try await loadImage(from: provider)
            } catch {
                continue
            }

            guard let data = await encodeJpegData(image: image, compressionQuality: 0.9) else { continue }
            let uuid = String(UUID().uuidString.prefix(8))
            let attachment = ChatAttachmentBuilder.makeAttachment(
                filename: "photo_\(uuid).jpg",
                mimeType: "image/jpeg",
                data: data
            )
            viewModel.addAttachment(attachment: attachment)
        }
    }

    private func encodeJpegData(image: UIImage, compressionQuality: CGFloat) async -> Data? {
        await withCheckedContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                let data = autoreleasepool {
                    image.jpegData(compressionQuality: compressionQuality)
                }
                continuation.resume(returning: data)
            }
        }
    }

    private func loadImage(from provider: NSItemProvider) async throws -> UIImage {
        try await withCheckedThrowingContinuation { continuation in
            provider.loadObject(ofClass: UIImage.self) { object, error in
                if let error {
                    continuation.resume(throwing: error)
                    return
                }
                if let image = object as? UIImage {
                    continuation.resume(returning: image)
                    return
                }
                continuation.resume(throwing: NSError(domain: "oc-pocket.ChatUIKit", code: 1))
            }
        }
    }

    private func presentDocumentPicker() {
        let remaining = remainingAttachmentSlots()
        guard remaining > 0 else { return }

        var contentTypes: [UTType] = [.image, .pdf, .plainText]
        let markdownCandidates: [UTType?] = [
            UTType(filenameExtension: "md"),
            UTType(filenameExtension: "markdown"),
            UTType("net.daringfireball.markdown"),
        ]
        for candidate in markdownCandidates {
            guard let type = candidate, !contentTypes.contains(type) else { continue }
            contentTypes.append(type)
        }
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: contentTypes, asCopy: true)
        picker.allowsMultipleSelection = true
        picker.delegate = self
        present(picker, animated: true)
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        controller.dismiss(animated: true)
        guard !urls.isEmpty else { return }

        Task { [weak self] in
            await self?.addFiles(from: urls)
        }
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        controller.dismiss(animated: true)
    }

    private func addFiles(from urls: [URL]) async {
        let remaining = remainingAttachmentSlots()
        guard remaining > 0 else { return }

        for url in urls.prefix(remaining) {
            let filename = url.lastPathComponent.isEmpty ? "file" : url.lastPathComponent
            do {
                let data = try await ChatAttachmentBuilder.loadFileData(from: url)
                let attachment = ChatAttachmentBuilder.makeAttachment(filename: filename, data: data)
                viewModel.addAttachment(attachment: attachment)
            } catch let error as ChatAttachmentBuilderError {
                switch error {
                case .fileTooLarge(let sizeBytes, let maxBytes):
                    viewModel.setAttachmentError(
                        error: AttachmentError.FileTooLarge(
                            filename: filename,
                            sizeBytes: sizeBytes,
                            maxBytes: maxBytes
                        )
                    )
                }
                continue
            } catch {
                print("Failed to load file attachment '\(filename)': \(error)")
                continue
            }
        }
    }
}

extension ChatViewController: UIAdaptivePresentationControllerDelegate {
    func presentationControllerDidDismiss(_ presentationController: UIPresentationController) {
        let dismissedController = presentationController.presentedViewController

        if let alert = dismissedController as? UIAlertController {
            switch alert.preferredStyle {
            case .actionSheet:
                if didSelectMessageActionSheetAction {
                    didSelectMessageActionSheetAction = false
                    return
                }
                viewModel.dismissMessageAction()
                return
            case .alert:
                if didSelectRevertConfirmationAction {
                    didSelectRevertConfirmationAction = false
                    return
                }
                viewModel.cancelRevert()
                return
            @unknown default:
                break
            }
        }

        viewModel.dismissMessageAction()
    }
}
