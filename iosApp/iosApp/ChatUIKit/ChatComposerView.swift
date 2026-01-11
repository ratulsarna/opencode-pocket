import UIKit
import ComposeApp

@MainActor
final class ChatComposerView: UIView, UITextViewDelegate, UITableViewDataSource, UITableViewDelegate {
    var onPickPhotos: (() -> Void)?
    var onPickFiles: (() -> Void)?
    var onAddFromClipboard: (() -> Void)?
    var onRemoveAttachment: ((Attachment) -> Void)?
    var onSelectMentionSuggestion: ((VaultEntry) -> Void)?
    var onSelectSlashCommandSuggestion: ((CommandInfo) -> Void)?
    var onTextAndCursorChange: ((_ text: String, _ cursorPosition: Int) -> Void)?
    var onSend: (() -> Void)?
    var onAbort: (() -> Void)?
    var onSelectThinkingVariant: ((_ variant: String?) -> Void)?

    private let blurView = UIVisualEffectView(effect: UIBlurEffect(style: .systemUltraThinMaterial))

    private let mainStack = UIStackView()
    private let commandContainer = UIView()
    private let commandTableView = UITableView(frame: .zero, style: .plain)
    private var commandHeightConstraint: NSLayoutConstraint?
    private var commandSuggestions: [CommandInfo] = []
    private var commandIsLoading: Bool = false
    private var commandError: String?
    private let mentionContainer = UIView()
    private let mentionTableView = UITableView(frame: .zero, style: .plain)
    private var mentionHeightConstraint: NSLayoutConstraint?
    private var mentionSuggestions: [VaultEntry] = []
    private var mentionIsLoading: Bool = false
    private var mentionError: String?

    private let attachmentsScrollView = UIScrollView()
    private let attachmentsStack = UIStackView()
    private var renderedChipsToken: String?
    private var maxFilesPerMessage: Int { Int(AttachmentLimits.shared.MAX_FILES_PER_MESSAGE) }

    private let inputContainer = UIView()
    private let plusButton = UIButton(type: .system)
    private let textView = UITextView()
    private let placeholderLabel = UILabel()

    private let sendButton = UIButton(type: .system)

    private var textViewHeightConstraint: NSLayoutConstraint?
    private var isApplyingState: Bool = false
    private var isStopMode: Bool = false

    private let composerMinHeight: CGFloat = 44
    private let composerMaxHeight: CGFloat = 120

    override init(frame: CGRect) {
        super.init(frame: frame)
        setUp()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    private func setUp() {
        backgroundColor = .clear

        blurView.layer.cornerRadius = 26
        blurView.layer.cornerCurve = .continuous
        blurView.clipsToBounds = true

        layer.shadowColor = UIColor.black.cgColor
        layer.shadowOpacity = 0.18
        layer.shadowRadius = 18
        layer.shadowOffset = CGSize(width: 0, height: 10)
        layer.masksToBounds = false

        blurView.translatesAutoresizingMaskIntoConstraints = false
        addSubview(blurView)
        NSLayoutConstraint.activate([
            blurView.topAnchor.constraint(equalTo: topAnchor),
            blurView.leadingAnchor.constraint(equalTo: leadingAnchor),
            blurView.trailingAnchor.constraint(equalTo: trailingAnchor),
            blurView.bottomAnchor.constraint(equalTo: bottomAnchor),
        ])

        mainStack.axis = .vertical
        mainStack.spacing = 8
        mainStack.layoutMargins = UIEdgeInsets(top: 0, left: 0, bottom: 0, right: 0)
        mainStack.isLayoutMarginsRelativeArrangement = true

        commandContainer.backgroundColor = .secondarySystemBackground
        commandContainer.layer.cornerRadius = 14
        commandContainer.layer.cornerCurve = .continuous
        commandContainer.clipsToBounds = true
        commandContainer.isHidden = true

        commandTableView.dataSource = self
        commandTableView.delegate = self
        commandTableView.backgroundColor = .clear
        commandTableView.separatorInset = UIEdgeInsets(top: 0, left: 12, bottom: 0, right: 12)
        commandTableView.rowHeight = 44
        commandTableView.estimatedRowHeight = 44
        commandTableView.tableFooterView = UIView()

        commandContainer.addSubview(commandTableView)
        commandTableView.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            commandTableView.topAnchor.constraint(equalTo: commandContainer.topAnchor),
            commandTableView.leadingAnchor.constraint(equalTo: commandContainer.leadingAnchor),
            commandTableView.trailingAnchor.constraint(equalTo: commandContainer.trailingAnchor),
            commandTableView.bottomAnchor.constraint(equalTo: commandContainer.bottomAnchor),
        ])
        commandHeightConstraint = commandContainer.heightAnchor.constraint(equalToConstant: 0)
        commandHeightConstraint?.isActive = true

        mentionContainer.backgroundColor = .secondarySystemBackground
        mentionContainer.layer.cornerRadius = 14
        mentionContainer.layer.cornerCurve = .continuous
        mentionContainer.clipsToBounds = true
        mentionContainer.isHidden = true

        mentionTableView.dataSource = self
        mentionTableView.delegate = self
        mentionTableView.backgroundColor = .clear
        mentionTableView.separatorInset = UIEdgeInsets(top: 0, left: 44, bottom: 0, right: 12)
        mentionTableView.rowHeight = 44
        mentionTableView.estimatedRowHeight = 44
        mentionTableView.tableFooterView = UIView()

        mentionContainer.addSubview(mentionTableView)
        mentionTableView.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            mentionTableView.topAnchor.constraint(equalTo: mentionContainer.topAnchor),
            mentionTableView.leadingAnchor.constraint(equalTo: mentionContainer.leadingAnchor),
            mentionTableView.trailingAnchor.constraint(equalTo: mentionContainer.trailingAnchor),
            mentionTableView.bottomAnchor.constraint(equalTo: mentionContainer.bottomAnchor),
        ])
        mentionHeightConstraint = mentionContainer.heightAnchor.constraint(equalToConstant: 0)
        mentionHeightConstraint?.isActive = true

        attachmentsScrollView.showsHorizontalScrollIndicator = false
        attachmentsScrollView.alwaysBounceHorizontal = true
        attachmentsScrollView.isHidden = true

        attachmentsStack.axis = .horizontal
        attachmentsStack.spacing = 8
        attachmentsStack.alignment = .center
        let attachmentsContentView = UIView()
        attachmentsScrollView.addSubview(attachmentsContentView)
        attachmentsContentView.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            attachmentsContentView.leadingAnchor.constraint(equalTo: attachmentsScrollView.contentLayoutGuide.leadingAnchor),
            attachmentsContentView.trailingAnchor.constraint(equalTo: attachmentsScrollView.contentLayoutGuide.trailingAnchor),
            attachmentsContentView.topAnchor.constraint(equalTo: attachmentsScrollView.contentLayoutGuide.topAnchor),
            attachmentsContentView.bottomAnchor.constraint(equalTo: attachmentsScrollView.contentLayoutGuide.bottomAnchor),
            attachmentsContentView.heightAnchor.constraint(equalTo: attachmentsScrollView.frameLayoutGuide.heightAnchor),
        ])

        attachmentsContentView.addSubview(attachmentsStack)
        attachmentsStack.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            attachmentsStack.leadingAnchor.constraint(equalTo: attachmentsContentView.leadingAnchor, constant: 8),
            attachmentsStack.trailingAnchor.constraint(equalTo: attachmentsContentView.trailingAnchor, constant: -8),
            attachmentsStack.centerYAnchor.constraint(equalTo: attachmentsContentView.centerYAnchor),
            attachmentsStack.topAnchor.constraint(greaterThanOrEqualTo: attachmentsContentView.topAnchor, constant: 2),
            attachmentsStack.bottomAnchor.constraint(lessThanOrEqualTo: attachmentsContentView.bottomAnchor, constant: -2),
        ])
        attachmentsScrollView.heightAnchor.constraint(equalToConstant: 44).isActive = true

        inputContainer.backgroundColor = .secondarySystemBackground
        inputContainer.layer.cornerRadius = 22
        inputContainer.layer.cornerCurve = .continuous
        inputContainer.clipsToBounds = true

        plusButton.setImage(UIImage(systemName: "plus"), for: .normal)
        plusButton.tintColor = .secondaryLabel
        plusButton.backgroundColor = UIColor.tertiarySystemBackground.withAlphaComponent(0.8)
        plusButton.layer.cornerRadius = 15
        plusButton.layer.cornerCurve = .continuous
        plusButton.showsMenuAsPrimaryAction = true

        textView.delegate = self
        textView.isEditable = true
        textView.isSelectable = true
        textView.isScrollEnabled = false
        textView.showsVerticalScrollIndicator = false
        textView.showsHorizontalScrollIndicator = false
        textView.backgroundColor = .clear
        textView.font = UIFont.preferredFont(forTextStyle: .body)
        textView.textColor = UIColor.label
        textView.textContainer.lineFragmentPadding = 0
        textView.textContainerInset = UIEdgeInsets(top: 10, left: 0, bottom: 10, right: 0)

        placeholderLabel.text = "Message OpenCode…"
        placeholderLabel.font = UIFont.preferredFont(forTextStyle: .body)
        placeholderLabel.textColor = UIColor.secondaryLabel
        placeholderLabel.translatesAutoresizingMaskIntoConstraints = false

        inputContainer.addSubview(plusButton)
        inputContainer.addSubview(textView)
        inputContainer.addSubview(placeholderLabel)
        textView.translatesAutoresizingMaskIntoConstraints = false
        plusButton.translatesAutoresizingMaskIntoConstraints = false

        NSLayoutConstraint.activate([
            plusButton.leadingAnchor.constraint(equalTo: inputContainer.leadingAnchor, constant: 10),
            plusButton.centerYAnchor.constraint(equalTo: inputContainer.centerYAnchor),
            plusButton.widthAnchor.constraint(equalToConstant: 30),
            plusButton.heightAnchor.constraint(equalToConstant: 30),

            textView.topAnchor.constraint(equalTo: inputContainer.topAnchor),
            textView.leadingAnchor.constraint(equalTo: plusButton.trailingAnchor, constant: 10),
            textView.bottomAnchor.constraint(equalTo: inputContainer.bottomAnchor),

            placeholderLabel.leadingAnchor.constraint(equalTo: textView.leadingAnchor),
            placeholderLabel.topAnchor.constraint(equalTo: inputContainer.topAnchor, constant: 10),
        ])

        let symbolConfig = UIImage.SymbolConfiguration(pointSize: 26, weight: .regular)
        sendButton.setImage(UIImage(systemName: "arrow.up.circle.fill", withConfiguration: symbolConfig), for: .normal)
        sendButton.tintColor = .systemBlue
        sendButton.addTarget(self, action: #selector(didTapSendOrStop), for: .touchUpInside)

        inputContainer.addSubview(sendButton)
        sendButton.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            sendButton.trailingAnchor.constraint(equalTo: inputContainer.trailingAnchor, constant: -10),
            sendButton.centerYAnchor.constraint(equalTo: inputContainer.centerYAnchor),
            sendButton.widthAnchor.constraint(equalToConstant: 30),
            sendButton.heightAnchor.constraint(equalToConstant: 30),
        ])

        NSLayoutConstraint.activate([
            textView.trailingAnchor.constraint(equalTo: sendButton.leadingAnchor, constant: -10),
            placeholderLabel.trailingAnchor.constraint(lessThanOrEqualTo: sendButton.leadingAnchor, constant: -10),
        ])

        textViewHeightConstraint = inputContainer.heightAnchor.constraint(equalToConstant: composerMinHeight)
        textViewHeightConstraint?.isActive = true

        mainStack.addArrangedSubview(commandContainer)
        mainStack.addArrangedSubview(mentionContainer)
        mainStack.addArrangedSubview(attachmentsScrollView)
        mainStack.addArrangedSubview(inputContainer)

        addSubview(mainStack)
        mainStack.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            mainStack.topAnchor.constraint(equalTo: topAnchor),
            mainStack.leadingAnchor.constraint(equalTo: leadingAnchor),
            mainStack.trailingAnchor.constraint(equalTo: trailingAnchor),
            mainStack.bottomAnchor.constraint(equalTo: bottomAnchor),
        ])
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        layer.shadowPath = UIBezierPath(roundedRect: bounds, cornerRadius: blurView.layer.cornerRadius).cgPath
    }

    func render(uiState: ChatUiState) {
        isApplyingState = true
        defer { isApplyingState = false }

        renderSlashCommandState(uiState.slashCommandState)
        renderMentionState(uiState.mentionState)
        renderChips(uiState: uiState)
        updateComposerTopInset()

        if textView.text != uiState.inputText {
            textView.text = uiState.inputText
        }

        // Cursor indices from Kotlin and UITextView are UTF-16 code unit offsets (not grapheme clusters).
        let clampedCursor = max(0, min(Int(uiState.inputCursor), uiState.inputText.utf16.count))
        if textView.selectedRange.location != clampedCursor {
            textView.selectedRange = NSRange(location: clampedCursor, length: 0)
        }

        placeholderLabel.isHidden = !uiState.inputText.isEmpty
        updatePlusMenu(uiState: uiState)

        let shouldStop = shouldShowStopButton(uiState)
        isStopMode = shouldStop
        let symbolConfig = UIImage.SymbolConfiguration(pointSize: 26, weight: .regular)
        let icon = shouldStop ? "stop.circle.fill" : "arrow.up.circle.fill"
        sendButton.setImage(UIImage(systemName: icon, withConfiguration: symbolConfig), for: .normal)
        sendButton.isEnabled = shouldStop || hasSendContent(uiState)

        updateHeight()
    }

    private func updateComposerTopInset() {
        let shouldShowTopInset = !(commandContainer.isHidden && mentionContainer.isHidden && attachmentsScrollView.isHidden)
        let desiredTop: CGFloat = shouldShowTopInset ? 4 : 0
        if mainStack.layoutMargins.top != desiredTop {
            mainStack.layoutMargins = UIEdgeInsets(top: desiredTop, left: 0, bottom: 0, right: 0)
        }
    }

    @objc private func didTapSendOrStop() {
        if isStopMode {
            onAbort?()
        } else {
            onSend?()
        }
    }

    func textViewDidBeginEditing(_ textView: UITextView) {
    }

    func textViewDidEndEditing(_ textView: UITextView) {
    }

    func textViewDidChange(_ textView: UITextView) {
        guard !isApplyingState else { return }
        onTextAndCursorChange?(textView.text ?? "", textView.selectedRange.location)
        placeholderLabel.isHidden = !(textView.text ?? "").isEmpty
        updateHeight()
    }

    func textViewDidChangeSelection(_ textView: UITextView) {
        guard !isApplyingState else { return }
        onTextAndCursorChange?(textView.text ?? "", textView.selectedRange.location)
    }

    private func updateHeight() {
        let targetWidth = max(1, textView.bounds.width)
        let fittingSize = CGSize(width: targetWidth, height: .greatestFiniteMagnitude)
        let size = textView.sizeThatFits(fittingSize)
        let clamped = min(composerMaxHeight, max(composerMinHeight, size.height))
        if abs((textViewHeightConstraint?.constant ?? 0) - clamped) > 0.5 {
            textViewHeightConstraint?.constant = clamped
            layoutIfNeeded()
        }
        textView.isScrollEnabled = size.height > composerMaxHeight
    }

    private func shouldShowStopButton(_ uiState: ChatUiState) -> Bool {
        uiState.isSending ||
            uiState.isAborting ||
            uiState.sessionStatus.name.uppercased() == "PROCESSING" ||
            uiState.streamingMessageId != nil
    }

    private func hasSendContent(_ uiState: ChatUiState) -> Bool {
        let hasText = !uiState.inputText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        let hasAttachments = !uiState.pendingAttachments.isEmpty
        return hasText || hasAttachments
    }

    private func updatePlusMenu(uiState: ChatUiState) {
        let remainingFiles = max(0, maxFilesPerMessage - uiState.pendingAttachments.count)
        let canAddAttachment = remainingFiles > 0

        let thinkingMenu: UIMenu? = makeThinkingMenu(uiState: uiState)

        let photos = UIAction(
            title: "Photos",
            image: UIImage(systemName: "photo"),
            attributes: canAddAttachment ? [] : [.disabled]
        ) { [weak self] _ in
            self?.onPickPhotos?()
        }

        let files = UIAction(
            title: "Files",
            image: UIImage(systemName: "paperclip"),
            attributes: canAddAttachment ? [] : [.disabled]
        ) { [weak self] _ in
            self?.onPickFiles?()
        }

        let paste = UIAction(
            title: "Paste from Clipboard",
            image: UIImage(systemName: "doc.on.clipboard"),
            attributes: canAddAttachment ? [] : [.disabled]
        ) { [weak self] _ in
            self?.onAddFromClipboard?()
        }

        let attachmentActions = UIMenu(options: [.displayInline], children: [photos, files, paste])
        if let thinkingMenu {
            plusButton.menu = UIMenu(children: [thinkingMenu, attachmentActions])
        } else {
            plusButton.menu = UIMenu(children: [attachmentActions])
        }
    }

    private func renderChips(uiState: ChatUiState) {
        let attachments = uiState.pendingAttachments
        let attachmentIds = attachments.map(\.id).joined(separator: "|")
        let thinkingVariant = uiState.thinkingVariant ?? ""
        let variantsKey = uiState.thinkingVariants.joined(separator: "|")
        let token = "\(attachmentIds)||\(thinkingVariant)||\(variantsKey)"
        if token == renderedChipsToken { return }
        renderedChipsToken = token

        for view in attachmentsStack.arrangedSubviews {
            attachmentsStack.removeArrangedSubview(view)
            view.removeFromSuperview()
        }

        let hasThinkingChip = uiState.thinkingVariant != nil
        let hasAttachments = !attachments.isEmpty
        let shouldShowRow = hasThinkingChip || hasAttachments
        attachmentsScrollView.isHidden = !shouldShowRow
        guard shouldShowRow else { return }

        if let variant = uiState.thinkingVariant {
            attachmentsStack.addArrangedSubview(makeThinkingVariantChip(uiState: uiState, variant: variant))
        }

        for attachment in attachments {
            attachmentsStack.addArrangedSubview(makeAttachmentChip(attachment: attachment))
        }
    }

    private func makeThinkingMenu(uiState: ChatUiState) -> UIMenu? {
        let variants = uiState.thinkingVariants
        guard !variants.isEmpty else { return nil }

        let current = uiState.thinkingVariant
        let actions: [UIAction] = buildThinkingVariantActions(current: current, variants: variants)
        return UIMenu(title: "Thinking", image: UIImage(systemName: "brain"), children: actions)
    }

    private func buildThinkingVariantActions(current: String?, variants: [String]) -> [UIAction] {
        var result: [UIAction] = []

        let auto = UIAction(
            title: "Auto",
            image: UIImage(systemName: "sparkles"),
            state: current == nil ? .on : .off
        ) { [weak self] _ in
            self?.onSelectThinkingVariant?(nil)
        }
        result.append(auto)

        for variant in variants {
            let title = variant.capitalized
            let action = UIAction(
                title: title,
                state: variant == current ? .on : .off
            ) { [weak self] _ in
                self?.onSelectThinkingVariant?(variant)
            }
            result.append(action)
        }

        return result
    }

    private func makeThinkingVariantChip(uiState: ChatUiState, variant: String) -> UIView {
        let button = UIButton(type: .system)
        button.showsMenuAsPrimaryAction = true
        button.menu = UIMenu(children: buildThinkingVariantActions(current: uiState.thinkingVariant, variants: uiState.thinkingVariants))

        let title = "Thinking: \(variant.capitalized)"
        button.setTitle(title, for: .normal)
        button.setTitleColor(.clear, for: .normal)
        button.titleLabel?.font = .preferredFont(forTextStyle: .caption1)

        button.backgroundColor = UIColor.systemPurple.withAlphaComponent(0.12)
        button.tintColor = .systemPurple
        button.layer.cornerRadius = 15
        button.layer.cornerCurve = .continuous
        button.clipsToBounds = true

        let stack = UIStackView()
        stack.axis = .horizontal
        stack.spacing = 6
        stack.alignment = .center
        stack.isUserInteractionEnabled = false

        let icon = UIImageView(image: UIImage(systemName: "brain"))
        icon.tintColor = .systemPurple
        icon.setContentHuggingPriority(.required, for: .horizontal)

        let label = UILabel()
        label.font = .preferredFont(forTextStyle: .caption1)
        label.textColor = .systemPurple
        label.text = title
        label.setContentHuggingPriority(.defaultLow, for: .horizontal)

        let chevronConfig = UIImage.SymbolConfiguration(pointSize: 11, weight: .semibold)
        let chevron = UIImageView(image: UIImage(systemName: "chevron.down", withConfiguration: chevronConfig))
        chevron.tintColor = UIColor.systemPurple.withAlphaComponent(0.9)
        chevron.setContentHuggingPriority(.required, for: .horizontal)

        stack.addArrangedSubview(icon)
        stack.addArrangedSubview(label)
        stack.addArrangedSubview(chevron)

        button.addSubview(stack)
        stack.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: button.topAnchor, constant: 8),
            stack.leadingAnchor.constraint(equalTo: button.leadingAnchor, constant: 10),
            stack.trailingAnchor.constraint(equalTo: button.trailingAnchor, constant: -10),
            stack.bottomAnchor.constraint(equalTo: button.bottomAnchor, constant: -8),
        ])

        button.accessibilityLabel = title

        return button
    }

    private func makeAttachmentChip(attachment: Attachment) -> UIView {
        let container = UIView()
        container.backgroundColor = .secondarySystemBackground
        container.layer.cornerRadius = 12
        container.layer.cornerCurve = .continuous
        container.clipsToBounds = true

        let stack = UIStackView()
        stack.axis = .horizontal
        stack.spacing = 6
        stack.alignment = .center

        let iconView = UIImageView()
        iconView.contentMode = .scaleAspectFill
        iconView.clipsToBounds = true

        if attachment.isImage {
            let bytes = attachment.thumbnailBytes ?? attachment.bytes
            let data = bytes.toData()
            if let image = ImageDownsampler.makeThumbnailImage(data: data, maxPixelSize: 64) {
                iconView.image = image
            } else {
                iconView.image = UIImage(systemName: "photo")
                iconView.tintColor = .secondaryLabel
                iconView.contentMode = .scaleAspectFit
            }
        } else {
            iconView.image = UIImage(systemName: "doc")
            iconView.tintColor = .secondaryLabel
            iconView.contentMode = .scaleAspectFit
        }

        iconView.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            iconView.widthAnchor.constraint(equalToConstant: 22),
            iconView.heightAnchor.constraint(equalToConstant: 22),
        ])
        iconView.layer.cornerRadius = 5

        let label = UILabel()
        label.font = .preferredFont(forTextStyle: .caption1)
        label.textColor = .secondaryLabel
        label.text = attachment.filename
        label.lineBreakMode = .byTruncatingMiddle

        let removeButton = UIButton(type: .system)
        removeButton.setImage(UIImage(systemName: "xmark.circle.fill"), for: .normal)
        removeButton.tintColor = .secondaryLabel
        removeButton.addAction(
            UIAction { [weak self] _ in
                self?.onRemoveAttachment?(attachment)
            },
            for: .touchUpInside
        )

        stack.addArrangedSubview(iconView)
        stack.addArrangedSubview(label)
        stack.addArrangedSubview(removeButton)

        container.addSubview(stack)
        stack.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: container.topAnchor, constant: 8),
            stack.leadingAnchor.constraint(equalTo: container.leadingAnchor, constant: 10),
            stack.trailingAnchor.constraint(equalTo: container.trailingAnchor, constant: -10),
            stack.bottomAnchor.constraint(equalTo: container.bottomAnchor, constant: -8),
        ])

        return container
    }

    private func renderMentionState(_ state: MentionState) {
        switch ComposeApp.onEnum(of: state) {
        case .inactive:
            mentionSuggestions = []
            mentionIsLoading = false
            mentionError = nil
            mentionContainer.isHidden = true
            mentionHeightConstraint?.constant = 0
            mentionTableView.reloadData()
            return

        case .active(let payload):
            mentionSuggestions = payload.suggestions
            mentionIsLoading = payload.isLoading
            mentionError = payload.error
        }

        mentionContainer.isHidden = false
        mentionTableView.reloadData()

        let rowCount: Int
        if mentionIsLoading && mentionSuggestions.isEmpty {
            rowCount = 1
        } else if let mentionError, !mentionError.isEmpty {
            rowCount = 1
        } else if mentionSuggestions.isEmpty {
            rowCount = 1
        } else {
            rowCount = mentionSuggestions.count
        }

        let visibleRows = min(6, rowCount)
        mentionHeightConstraint?.constant = CGFloat(visibleRows) * 44
    }

    private func renderSlashCommandState(_ state: SlashCommandState) {
        switch ComposeApp.onEnum(of: state) {
        case .inactive:
            commandSuggestions = []
            commandIsLoading = false
            commandError = nil
            commandContainer.isHidden = true
            commandHeightConstraint?.constant = 0
            commandTableView.reloadData()
            return

        case .active(let payload):
            commandSuggestions = payload.suggestions
            commandIsLoading = payload.isLoading
            commandError = payload.error
        }

        commandContainer.isHidden = false
        commandTableView.reloadData()

        let rowCount: Int
        if commandIsLoading && commandSuggestions.isEmpty {
            rowCount = 1
        } else if let commandError, !commandError.isEmpty {
            rowCount = 1
        } else if commandSuggestions.isEmpty {
            rowCount = 1
        } else {
            rowCount = commandSuggestions.count
        }

        let visibleRows = min(6, rowCount)
        commandHeightConstraint?.constant = CGFloat(visibleRows) * 44
    }

    // MARK: - Mention suggestions table

    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        if tableView == mentionTableView {
            guard !mentionContainer.isHidden else { return 0 }
            if mentionIsLoading && mentionSuggestions.isEmpty { return 1 }
            if let mentionError, !mentionError.isEmpty { return 1 }
            if mentionSuggestions.isEmpty { return 1 }
            return mentionSuggestions.count
        }

        if tableView == commandTableView {
            guard !commandContainer.isHidden else { return 0 }
            if commandIsLoading && commandSuggestions.isEmpty { return 1 }
            if let commandError, !commandError.isEmpty { return 1 }
            if commandSuggestions.isEmpty { return 1 }
            return commandSuggestions.count
        }

        return 0
    }

    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        if tableView == commandTableView {
            let identifier = "CommandCell"
            let cell = tableView.dequeueReusableCell(withIdentifier: identifier) ?? UITableViewCell(style: .subtitle, reuseIdentifier: identifier)
            cell.backgroundColor = .clear
            cell.selectionStyle = .default
            cell.accessoryType = .none
            cell.accessoryView = nil
            cell.imageView?.image = nil

            if commandIsLoading && commandSuggestions.isEmpty {
                cell.textLabel?.text = "Loading commands…"
                cell.textLabel?.textColor = .secondaryLabel
                cell.detailTextLabel?.text = nil
                let spinner = UIActivityIndicatorView(style: .medium)
                spinner.startAnimating()
                cell.accessoryView = spinner
                cell.selectionStyle = .none
                return cell
            }

            if let commandError, !commandError.isEmpty {
                cell.textLabel?.text = commandError
                cell.textLabel?.textColor = .systemRed
                cell.detailTextLabel?.text = nil
                cell.selectionStyle = .none
                return cell
            }

            guard !commandSuggestions.isEmpty else {
                cell.textLabel?.text = "No commands"
                cell.textLabel?.textColor = .secondaryLabel
                cell.detailTextLabel?.text = nil
                cell.selectionStyle = .none
                return cell
            }

            let cmd = commandSuggestions[indexPath.row]
            cell.textLabel?.text = "/\(cmd.name)"
            cell.textLabel?.textColor = .label
            cell.detailTextLabel?.text = cmd.description_ ?? ""
            cell.detailTextLabel?.textColor = .secondaryLabel

            return cell
        }

        let identifier = "MentionCell"
        let cell = tableView.dequeueReusableCell(withIdentifier: identifier) ?? UITableViewCell(style: .subtitle, reuseIdentifier: identifier)
        cell.backgroundColor = .clear
        cell.selectionStyle = .default
        cell.accessoryType = .none
        cell.accessoryView = nil

        if mentionIsLoading && mentionSuggestions.isEmpty {
            cell.textLabel?.text = "Searching…"
            cell.textLabel?.textColor = .secondaryLabel
            cell.detailTextLabel?.text = nil
            let spinner = UIActivityIndicatorView(style: .medium)
            spinner.startAnimating()
            cell.accessoryView = spinner
            cell.imageView?.image = UIImage(systemName: "magnifyingglass")
            cell.imageView?.tintColor = .secondaryLabel
            cell.selectionStyle = .none
            return cell
        }

        if let mentionError, !mentionError.isEmpty {
            cell.textLabel?.text = mentionError
            cell.textLabel?.textColor = .systemRed
            cell.detailTextLabel?.text = nil
            cell.imageView?.image = UIImage(systemName: "exclamationmark.triangle.fill")
            cell.imageView?.tintColor = .systemRed
            cell.selectionStyle = .none
            return cell
        }

        guard !mentionSuggestions.isEmpty else {
            cell.textLabel?.text = "No matches"
            cell.textLabel?.textColor = .secondaryLabel
            cell.detailTextLabel?.text = nil
            cell.imageView?.image = UIImage(systemName: "magnifyingglass")
            cell.imageView?.tintColor = .secondaryLabel
            cell.selectionStyle = .none
            return cell
        }

        let entry = mentionSuggestions[indexPath.row]
        cell.textLabel?.text = displayName(path: entry.path)
        cell.textLabel?.textColor = .label
        cell.detailTextLabel?.text = entry.path
        cell.detailTextLabel?.textColor = .secondaryLabel
        cell.imageView?.image = UIImage(systemName: entry.isDirectory ? "folder" : "doc")
        cell.imageView?.tintColor = .secondaryLabel

        return cell
    }

    func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        if tableView == commandTableView {
            if commandIsLoading && commandSuggestions.isEmpty { return }
            if let commandError, !commandError.isEmpty { return }
            guard !commandSuggestions.isEmpty else { return }
            guard commandSuggestions.indices.contains(indexPath.row) else { return }
            onSelectSlashCommandSuggestion?(commandSuggestions[indexPath.row])
            textView.becomeFirstResponder()
            return
        }

        guard !mentionSuggestions.isEmpty else { return }
        guard mentionSuggestions.indices.contains(indexPath.row) else { return }
        onSelectMentionSuggestion?(mentionSuggestions[indexPath.row])
        textView.becomeFirstResponder()
    }

    private func displayName(path: String) -> String {
        let name = path.split(separator: "/").last.map(String.init) ?? path
        return name.isEmpty ? path : name
    }
}
