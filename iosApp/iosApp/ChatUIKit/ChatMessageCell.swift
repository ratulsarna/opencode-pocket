import UIKit
import SafariServices
import ComposeApp

final class ChatMessageCell: UICollectionViewCell {
    static let reuseIdentifier = "ChatMessageCell"

    private static let bubbleWidthMultiplier: CGFloat = 0.88
    private static let bubbleMaxWidth: CGFloat = 420
    private static let bubbleContentInset: CGFloat = 12

    private let bubbleView = UIView()
    private let contentStack = UIStackView()

    private let partsStackView = UIStackView()

    private let assistantErrorStack = UIStackView()
    private let assistantErrorIcon = UIImageView()
    private let assistantErrorLabel = UILabel()

    private let footerRow = UIStackView()
    private let timestampLabel = UILabel()
    private let actionsButton = UIButton(type: .system)

    private var userConstraints: [NSLayoutConstraint] = []
    private var assistantConstraints: [NSLayoutConstraint] = []

    private var onShowActions: ((UIView) -> Void)?
    private var onOpenFile: ((String) -> Void)?
    private var expandedPartKeys: Set<String> = []
    private var collapsedPartKeys: Set<String> = []
    private var alwaysExpandAssistantParts = false
    private var onToggleExpandablePart: ((String, Bool) -> Void)?

    override init(frame: CGRect) {
        super.init(frame: frame)

        contentView.backgroundColor = .clear
        backgroundColor = .clear

        bubbleView.layer.cornerRadius = 16
        bubbleView.layer.cornerCurve = .continuous
        bubbleView.layer.borderWidth = 1
        bubbleView.layer.borderColor = UIColor.separator.withAlphaComponent(0.25).cgColor

        contentStack.axis = .vertical
        contentStack.spacing = 8

        partsStackView.axis = .vertical
        partsStackView.spacing = 10

        assistantErrorStack.axis = .horizontal
        assistantErrorStack.spacing = 6
        assistantErrorStack.alignment = .center
        assistantErrorStack.isHidden = true

        assistantErrorIcon.image = UIImage(systemName: "exclamationmark.triangle.fill")
        assistantErrorIcon.tintColor = .systemRed
        assistantErrorIcon.setContentHuggingPriority(.required, for: .horizontal)

        assistantErrorLabel.font = .preferredFont(forTextStyle: .caption1)
        assistantErrorLabel.textColor = .systemRed
        assistantErrorLabel.numberOfLines = 0

        assistantErrorStack.addArrangedSubview(assistantErrorIcon)
        assistantErrorStack.addArrangedSubview(assistantErrorLabel)

        footerRow.axis = .horizontal
        footerRow.spacing = 6
        footerRow.alignment = .center

        timestampLabel.font = .preferredFont(forTextStyle: .caption2)
        timestampLabel.textColor = .secondaryLabel

        var actionsConfig = UIButton.Configuration.plain()
        actionsConfig.image = UIImage(systemName: "ellipsis")
        actionsConfig.baseForegroundColor = .secondaryLabel
        actionsConfig.contentInsets = NSDirectionalEdgeInsets(top: 4, leading: 6, bottom: 4, trailing: 6)
        actionsButton.configuration = actionsConfig
        actionsButton.isHidden = true
        actionsButton.addTarget(self, action: #selector(didTapActions), for: .touchUpInside)

        footerRow.addArrangedSubview(UIView())
        footerRow.addArrangedSubview(timestampLabel)
        footerRow.addArrangedSubview(actionsButton)

        bubbleView.addSubview(contentStack)
        contentView.addSubview(bubbleView)

        contentStack.translatesAutoresizingMaskIntoConstraints = false
        bubbleView.translatesAutoresizingMaskIntoConstraints = false

        NSLayoutConstraint.activate([
            contentStack.topAnchor.constraint(equalTo: bubbleView.topAnchor, constant: 12),
            contentStack.leadingAnchor.constraint(equalTo: bubbleView.leadingAnchor, constant: 12),
            contentStack.trailingAnchor.constraint(equalTo: bubbleView.trailingAnchor, constant: -12),
            contentStack.bottomAnchor.constraint(equalTo: bubbleView.bottomAnchor, constant: -12),

            bubbleView.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 8),
            bubbleView.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -8),
        ])

        userConstraints = [
            bubbleView.leadingAnchor.constraint(greaterThanOrEqualTo: contentView.leadingAnchor, constant: 12),
            bubbleView.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -12),
        ]
        assistantConstraints = [
            bubbleView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 12),
            bubbleView.trailingAnchor.constraint(lessThanOrEqualTo: contentView.trailingAnchor, constant: -12),
        ]

        // Match Compose: mobile bubbles are near-full width but capped.
        NSLayoutConstraint.activate([
            bubbleView.widthAnchor.constraint(lessThanOrEqualTo: contentView.widthAnchor, multiplier: Self.bubbleWidthMultiplier),
            bubbleView.widthAnchor.constraint(lessThanOrEqualToConstant: Self.bubbleMaxWidth),
        ])

        let preferredWidthMultiplier = bubbleView.widthAnchor.constraint(equalTo: contentView.widthAnchor, multiplier: Self.bubbleWidthMultiplier)
        preferredWidthMultiplier.priority = UILayoutPriority(750)
        preferredWidthMultiplier.isActive = true

        let preferredWidthConstant = bubbleView.widthAnchor.constraint(equalToConstant: Self.bubbleMaxWidth)
        preferredWidthConstant.priority = UILayoutPriority(749)
        preferredWidthConstant.isActive = true

        contentStack.addArrangedSubview(partsStackView)
        contentStack.addArrangedSubview(assistantErrorStack)
        contentStack.addArrangedSubview(footerRow)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func prepareForReuse() {
        super.prepareForReuse()
        onShowActions = nil
        onOpenFile = nil
        onToggleExpandablePart = nil
        expandedPartKeys = []
        collapsedPartKeys = []
        alwaysExpandAssistantParts = false
        updateIntrinsicTextWidthHints(contentViewWidth: 0)
        for view in partsStackView.arrangedSubviews {
            partsStackView.removeArrangedSubview(view)
            view.removeFromSuperview()
        }
    }

    override func preferredLayoutAttributesFitting(_ layoutAttributes: UICollectionViewLayoutAttributes) -> UICollectionViewLayoutAttributes {
        let attributes = super.preferredLayoutAttributesFitting(layoutAttributes)

        updateIntrinsicTextWidthHints(contentViewWidth: layoutAttributes.size.width)

        let targetSize = CGSize(width: layoutAttributes.size.width, height: UIView.layoutFittingCompressedSize.height)
        let size = contentView.systemLayoutSizeFitting(
            targetSize,
            withHorizontalFittingPriority: .required,
            verticalFittingPriority: .fittingSizeLevel
        )
        attributes.size.height = ceil(size.height)
        return attributes
    }

    private func updateIntrinsicTextWidthHints(contentViewWidth: CGFloat) {
        guard contentViewWidth > 0 else {
            var textViews: [IntrinsicTextView] = []
            collectIntrinsicTextViews(in: contentStack, into: &textViews)
            for textView in textViews {
                textView.layoutWidthHint = nil
            }
            return
        }

        let bubbleWidth = min(contentViewWidth * Self.bubbleWidthMultiplier, Self.bubbleMaxWidth)
        let contentWidth = max(1, bubbleWidth - (Self.bubbleContentInset * 2))

        var textViews: [IntrinsicTextView] = []
        collectIntrinsicTextViews(in: contentStack, into: &textViews)

        for textView in textViews {
            let margins = horizontalLayoutMargins(between: textView, and: contentStack)
            textView.layoutWidthHint = max(1, contentWidth - margins)
        }
    }

    private func collectIntrinsicTextViews(in view: UIView, into output: inout [IntrinsicTextView]) {
        if let textView = view as? IntrinsicTextView {
            output.append(textView)
            return
        }
        for subview in view.subviews {
            collectIntrinsicTextViews(in: subview, into: &output)
        }
    }

    private func horizontalLayoutMargins(between view: UIView, and stopView: UIView) -> CGFloat {
        var total: CGFloat = 0
        var current = view.superview

        while let currentView = current, currentView !== stopView {
            if let stack = currentView as? UIStackView, stack.isLayoutMarginsRelativeArrangement {
                total += stack.layoutMargins.left + stack.layoutMargins.right
            }
            current = currentView.superview
        }

        return total
    }

    func configure(
        message: Message?,
        uiState: ChatUiState?,
        expandedPartKeys: Set<String>,
        collapsedPartKeys: Set<String>,
        alwaysExpandAssistantParts: Bool,
        onToggleExpandablePart: @escaping (String, Bool) -> Void,
        onShowActions: @escaping (UIView) -> Void,
        onOpenFile: @escaping (String) -> Void
    ) {
        self.onShowActions = onShowActions
        self.onOpenFile = onOpenFile
        self.expandedPartKeys = expandedPartKeys
        self.collapsedPartKeys = collapsedPartKeys
        self.alwaysExpandAssistantParts = alwaysExpandAssistantParts
        self.onToggleExpandablePart = onToggleExpandablePart

        guard let message, let uiState else {
            for view in partsStackView.arrangedSubviews {
                partsStackView.removeArrangedSubview(view)
                view.removeFromSuperview()
            }
            assistantErrorStack.isHidden = true
            timestampLabel.text = nil
            actionsButton.isHidden = true
            return
        }

        let isUser = message is UserMessage
        setIsUser(isUser)

        renderParts(
            message: message,
            assistantVisibility: uiState.assistantResponsePartVisibility,
            isUser: isUser
        )

        if let assistant = message as? AssistantMessage, let apiError = assistant.error {
            assistantErrorLabel.text = apiError.message ?? "An error occurred."
            assistantErrorStack.isHidden = false
        } else {
            assistantErrorLabel.text = nil
            assistantErrorStack.isHidden = true
        }

        timestampLabel.text = KmpDateFormat.mediumDateTime(message.createdAt)
        // Intentional: actions are currently only supported for user messages (revert/fork/copy).
        // Assistant messages are selection-only for now.
        actionsButton.isHidden = !isUser

        configureAccessibility(
            message: message,
            assistantVisibility: uiState.assistantResponsePartVisibility,
            isUser: isUser
        )
    }

    @objc private func didTapActions() {
        onShowActions?(actionsButton)
    }

    private func setIsUser(_ isUser: Bool) {
        NSLayoutConstraint.deactivate(userConstraints + assistantConstraints)
        if isUser {
            NSLayoutConstraint.activate(userConstraints)
            bubbleView.backgroundColor = UIColor.systemBlue.withAlphaComponent(0.20)
        } else {
            NSLayoutConstraint.activate(assistantConstraints)
            bubbleView.backgroundColor = UIColor.secondarySystemBackground
        }
    }

    private func renderParts(
        message: Message,
        assistantVisibility: AssistantResponsePartVisibility,
        isUser: Bool
    ) {
        for view in partsStackView.arrangedSubviews {
            partsStackView.removeArrangedSubview(view)
            view.removeFromSuperview()
        }

        let visibleParts: [MessagePart]
        if isUser {
            visibleParts = message.parts.filter { part in
                part is TextPart || part is FilePart
            }
        } else {
            visibleParts = MessagePartVisibilityKt.filterVisibleParts(
                parts: message.parts,
                visibility: assistantVisibility
            )
        }

        for (index, part) in visibleParts.enumerated() {
            if let text = part as? TextPart {
                if !text.text.isEmpty {
                    partsStackView.addArrangedSubview(makeMarkdownView(text: text.text, isSecondary: false))
                }
            } else if let reasoning = part as? ReasoningPart {
                if !reasoning.text.isEmpty {
                    let key = partKey(messageId: message.id, type: "reasoning", partId: reasoning.id, index: index)
                    partsStackView.addArrangedSubview(makeReasoningPartView(text: reasoning.text, key: key))
                }
            } else if let tool = part as? ToolPart {
                let key = partKey(messageId: message.id, type: "tool", partId: tool.id, index: index)
                partsStackView.addArrangedSubview(makeToolPartView(tool, key: key))
            } else if let file = part as? FilePart {
                partsStackView.addArrangedSubview(makeFilePartView(file))
            } else if let patch = part as? PatchPart {
                partsStackView.addArrangedSubview(makePatchPartView(patch))
            } else if let agent = part as? AgentPart {
                partsStackView.addArrangedSubview(makeBadgeView(text: "Agent: \(agent.name)", systemImage: "person.fill"))
            } else if let retry = part as? RetryPart {
                partsStackView.addArrangedSubview(makeBadgeView(text: "Retry (attempt \(retry.attempt))", systemImage: "arrow.clockwise"))
                if let message = retry.error?.message, !message.isEmpty {
                    partsStackView.addArrangedSubview(makeTextView(text: message, isSecondary: true))
                }
            } else if let compaction = part as? CompactionPart {
                partsStackView.addArrangedSubview(makeBadgeView(text: compaction.auto ? "Context compaction (auto)" : "Context compaction", systemImage: "arrow.up.left.and.arrow.down.right"))
            } else if part is StepStartPart || part is StepFinishPart || part is SnapshotPart {
                continue
            } else if let unknown = part as? UnknownPart {
                partsStackView.addArrangedSubview(makeBadgeView(text: "Unknown part: \(unknown.type)", systemImage: "questionmark.circle"))
                if !unknown.rawData.isEmpty {
                    partsStackView.addArrangedSubview(makeTextView(text: unknown.rawData, isSecondary: true))
                }
            }
        }
    }

    private func partKey(messageId: String, type: String, partId: String?, index: Int) -> String {
        let suffix = partId?.isEmpty == false ? partId! : "idx:\(index)"
        return "\(messageId)|\(type)|\(suffix)"
    }

    private func makeTextView(text: String, isSecondary: Bool) -> IntrinsicTextView {
        let textView = IntrinsicTextView()
        textView.isEditable = false
        textView.isSelectable = true
        textView.isScrollEnabled = false
        textView.backgroundColor = .clear
        textView.isAccessibilityElement = false
        textView.delegate = self
        textView.dataDetectorTypes = []
        textView.textContainerInset = .zero
        textView.textContainer.lineFragmentPadding = 0
        textView.font = .preferredFont(forTextStyle: .body)
        textView.setContentCompressionResistancePriority(.required, for: .vertical)
        textView.attributedText = buildAttributedText(text: text, isSecondary: isSecondary)
        return textView
    }

    private func makeMarkdownView(text: String, isSecondary: Bool) -> UIView {
        let linkified = MarkdownInterop.shared.linkify(text: text)
        let blocks = OCMobileMarkdownRenderer.shared.renderBlocks(
            markdown: linkified,
            isSecondary: isSecondary,
            traitCollection: traitCollection
        )

        let container = UIStackView()
        container.axis = .vertical
        container.spacing = 8

        for block in blocks {
            container.addArrangedSubview(makeMarkdownBlockView(block, isSecondary: isSecondary))
        }

        if container.arrangedSubviews.isEmpty {
            container.addArrangedSubview(makeTextView(text: text, isSecondary: isSecondary))
        }

        return container
    }

    private func makeMarkdownBlockView(_ block: OCMobileMarkdownBlock, isSecondary: Bool) -> UIView {
        switch block.kind {
        case .codeBlock:
            return makeCodeBlockView(attributedText: block.attributedText ?? NSAttributedString())
        case .blockQuote:
            return makeBlockQuoteView(block: block, isSecondary: isSecondary)
        default:
            return makeAttributedTextView(attributedText: block.attributedText ?? NSAttributedString(), isSecondary: isSecondary)
        }
    }

    private func makeAttributedTextView(attributedText: NSAttributedString, isSecondary: Bool) -> IntrinsicTextView {
        let textView = IntrinsicTextView()
        textView.isEditable = false
        textView.isSelectable = true
        textView.isScrollEnabled = false
        textView.backgroundColor = .clear
        textView.isAccessibilityElement = false
        textView.delegate = self
        textView.dataDetectorTypes = []
        textView.textContainerInset = .zero
        textView.textContainer.lineFragmentPadding = 0
        textView.textContainer.lineBreakMode = .byWordWrapping
        textView.setContentCompressionResistancePriority(.required, for: .vertical)

        let base = NSMutableAttributedString(attributedString: attributedText)
        let fullRange = NSRange(location: 0, length: base.length)
        if base.length > 0, base.attribute(.foregroundColor, at: 0, effectiveRange: nil) == nil {
            base.addAttribute(.foregroundColor, value: isSecondary ? UIColor.secondaryLabel : UIColor.label, range: fullRange)
        }
        textView.attributedText = base
        return textView
    }

    private func makeCodeBlockView(attributedText: NSAttributedString) -> UIView {
        let container = UIStackView()
        container.axis = .vertical
        container.spacing = 0
        container.layoutMargins = UIEdgeInsets(top: 10, left: 10, bottom: 10, right: 10)
        container.isLayoutMarginsRelativeArrangement = true
        container.backgroundColor = UIColor.secondarySystemBackground.withAlphaComponent(0.9)
        container.layer.cornerRadius = 10
        container.layer.cornerCurve = .continuous

        let textView = makeAttributedTextView(attributedText: attributedText, isSecondary: false)
        container.addArrangedSubview(textView)

        return container
    }

    private func makeBlockQuoteView(block: OCMobileMarkdownBlock, isSecondary: Bool) -> UIView {
        let container = UIView()
        container.layer.cornerRadius = 10
        container.layer.cornerCurve = .continuous

        let contentStack = UIStackView()
        contentStack.axis = .vertical
        contentStack.spacing = 8
        contentStack.layoutMargins = UIEdgeInsets(top: 0, left: 13, bottom: 0, right: 0)
        contentStack.isLayoutMarginsRelativeArrangement = true

        if block.children.isEmpty {
            contentStack.addArrangedSubview(
                makeAttributedTextView(attributedText: block.attributedText ?? NSAttributedString(), isSecondary: isSecondary)
            )
        } else {
            for child in block.children {
                contentStack.addArrangedSubview(makeMarkdownBlockView(child, isSecondary: isSecondary))
            }
        }

        let bar = UIView()
        bar.backgroundColor = UIColor.tertiaryLabel.withAlphaComponent(0.6)
        bar.layer.cornerRadius = 1.5
        bar.layer.cornerCurve = .continuous

        container.addSubview(contentStack)
        container.addSubview(bar)

        contentStack.translatesAutoresizingMaskIntoConstraints = false
        bar.translatesAutoresizingMaskIntoConstraints = false

        NSLayoutConstraint.activate([
            contentStack.topAnchor.constraint(equalTo: container.topAnchor),
            contentStack.leadingAnchor.constraint(equalTo: container.leadingAnchor),
            contentStack.trailingAnchor.constraint(equalTo: container.trailingAnchor),
            contentStack.bottomAnchor.constraint(equalTo: container.bottomAnchor),

            bar.leadingAnchor.constraint(equalTo: container.leadingAnchor),
            bar.topAnchor.constraint(equalTo: container.topAnchor, constant: 2),
            bar.bottomAnchor.constraint(equalTo: container.bottomAnchor, constant: -2),
            bar.widthAnchor.constraint(equalToConstant: 3),
        ])

        return container
    }

    private func makeBadgeView(text: String, systemImage: String) -> UIView {
        let container = UIView()
        container.backgroundColor = UIColor.secondarySystemBackground.withAlphaComponent(0.9)
        container.layer.cornerRadius = 10
        container.layer.cornerCurve = .continuous

        let stack = UIStackView()
        stack.axis = .horizontal
        stack.spacing = 6
        stack.alignment = .center
        stack.layoutMargins = UIEdgeInsets(top: 8, left: 10, bottom: 8, right: 10)
        stack.isLayoutMarginsRelativeArrangement = true

        let imageView = UIImageView(image: UIImage(systemName: systemImage))
        imageView.tintColor = .secondaryLabel
        imageView.setContentHuggingPriority(.required, for: .horizontal)

        let label = UILabel()
        label.font = .preferredFont(forTextStyle: .caption1)
        label.textColor = .secondaryLabel
        label.text = text
        label.numberOfLines = 0

        stack.addArrangedSubview(imageView)
        stack.addArrangedSubview(label)

        container.addSubview(stack)
        stack.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: container.topAnchor),
            stack.leadingAnchor.constraint(equalTo: container.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: container.trailingAnchor),
            stack.bottomAnchor.constraint(equalTo: container.bottomAnchor),
        ])

        return container
    }

    private func makeReasoningPartView(text: String, key: String) -> UIView {
        let container = UIView()
        container.backgroundColor = UIColor.systemPurple.withAlphaComponent(0.12)
        container.layer.cornerRadius = 12
        container.layer.cornerCurve = .continuous
        container.clipsToBounds = true

        let stack = UIStackView()
        stack.axis = .vertical
        stack.spacing = 8
        stack.layoutMargins = UIEdgeInsets(top: 8, left: 10, bottom: 8, right: 10)
        stack.isLayoutMarginsRelativeArrangement = true

        let header = UIControl()
        let headerRow = UIStackView()
        headerRow.axis = .horizontal
        headerRow.spacing = 6
        headerRow.alignment = .center
        // Ensure taps hit the UIControl (not the stack view sub-hierarchy).
        headerRow.isUserInteractionEnabled = false

        let icon = UIImageView(image: UIImage(systemName: "brain"))
        icon.tintColor = .systemPurple
        icon.setContentHuggingPriority(.required, for: .horizontal)

        let title = UILabel()
        title.font = .preferredFont(forTextStyle: .caption1)
        title.textColor = .systemPurple
        title.text = "Thinking…"

        let chevron = UIImageView(image: UIImage(systemName: "chevron.down"))
        chevron.tintColor = .systemPurple
        chevron.setContentHuggingPriority(.required, for: .horizontal)

        headerRow.addArrangedSubview(icon)
        headerRow.addArrangedSubview(title)
        headerRow.addArrangedSubview(UIView())
        headerRow.addArrangedSubview(chevron)

        header.addSubview(headerRow)
        headerRow.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            headerRow.topAnchor.constraint(equalTo: header.topAnchor),
            headerRow.leadingAnchor.constraint(equalTo: header.leadingAnchor),
            headerRow.trailingAnchor.constraint(equalTo: header.trailingAnchor),
            headerRow.bottomAnchor.constraint(equalTo: header.bottomAnchor),
        ])

        let body = makeMarkdownView(text: text, isSecondary: true)
        let isExpanded = alwaysExpandAssistantParts
            ? !collapsedPartKeys.contains(key)
            : expandedPartKeys.contains(key)
        body.isHidden = !isExpanded
        title.text = isExpanded ? "Thinking" : "Thinking…"
        chevron.image = UIImage(systemName: isExpanded ? "chevron.up" : "chevron.down")

        header.addAction(
            UIAction { [weak self] _ in
                let willExpand = body.isHidden
                body.isHidden = !willExpand
                title.text = willExpand ? "Thinking" : "Thinking…"
                chevron.image = UIImage(systemName: willExpand ? "chevron.up" : "chevron.down")
                self?.onToggleExpandablePart?(key, willExpand)
                self?.requestLayoutUpdate()
            },
            for: .touchUpInside
        )

        stack.addArrangedSubview(header)
        stack.addArrangedSubview(body)

        container.addSubview(stack)
        stack.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: container.topAnchor),
            stack.leadingAnchor.constraint(equalTo: container.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: container.trailingAnchor),
            stack.bottomAnchor.constraint(equalTo: container.bottomAnchor),
        ])

        return container
    }

    private func makeToolPartView(_ part: ToolPart, key: String) -> UIView {
        let container = UIView()
        container.backgroundColor = toolBackgroundColor(part.state)
        container.layer.cornerRadius = 12
        container.layer.cornerCurve = .continuous
        container.clipsToBounds = true

        let stack = UIStackView()
        stack.axis = .vertical
        stack.spacing = 8
        stack.layoutMargins = UIEdgeInsets(top: 10, left: 10, bottom: 10, right: 10)
        stack.isLayoutMarginsRelativeArrangement = true

        let header = UIControl()
        let headerRow = UIStackView()
        headerRow.axis = .horizontal
        headerRow.spacing = 8
        headerRow.alignment = .center
        // Ensure taps hit the UIControl (not the stack view sub-hierarchy).
        headerRow.isUserInteractionEnabled = false

        let icon = UIImageView(image: UIImage(systemName: toolIconName(part.state)))
        icon.tintColor = toolTintColor(part.state)
        icon.setContentHuggingPriority(.required, for: .horizontal)

        let presentation = ToolPresentationFormatter.shared.format(
            tool: part.tool,
            state: part.state,
            inputJson: part.input,
            output: part.output,
            error: part.error,
            titleFromServer: part.title,
            metadataJson: part.metadata,
            maxPreviewLines: 4
        )

        let toolInput = parseToolInputJson(part.input)
        let toolFormat = (toolInput?["format"] as? String)?.lowercased()
        let webfetchUrl: String? = {
            guard part.tool.lowercased() == "webfetch" else { return nil }
            guard let raw = toolInput?["url"] as? String else { return nil }
            return normalizedHttpUrl(raw)
        }()

        let titleStack = UIStackView()
        titleStack.axis = .vertical
        titleStack.spacing = 2

        let title = UILabel()
        title.font = .preferredFont(forTextStyle: .callout)
        title.textColor = .label
        // Keep tool headers compact (especially for webfetch URLs).
        title.numberOfLines = 2
        title.lineBreakMode = .byTruncatingTail
        title.text = presentation.title

        let subtitle = UILabel()
        subtitle.font = .monospacedSystemFont(ofSize: UIFont.preferredFont(forTextStyle: .caption2).pointSize, weight: .regular)
        subtitle.textColor = .secondaryLabel
        subtitle.text = part.tool
        subtitle.numberOfLines = 1

        titleStack.addArrangedSubview(title)
        titleStack.addArrangedSubview(subtitle)

        let chevron = UIImageView(image: UIImage(systemName: "chevron.down"))
        chevron.tintColor = .secondaryLabel
        chevron.setContentHuggingPriority(.required, for: .horizontal)

        var webfetchOpenSlot: UIView?
        if webfetchUrl != nil {
            let slot = UIView()
            slot.translatesAutoresizingMaskIntoConstraints = false
            NSLayoutConstraint.activate([
                slot.widthAnchor.constraint(equalToConstant: 28),
                slot.heightAnchor.constraint(equalToConstant: 28),
            ])
            slot.setContentHuggingPriority(.required, for: .horizontal)
            slot.setContentCompressionResistancePriority(.required, for: .horizontal)
            webfetchOpenSlot = slot
        }

        headerRow.addArrangedSubview(icon)
        headerRow.addArrangedSubview(titleStack)
        headerRow.addArrangedSubview(UIView())
        if let webfetchOpenSlot {
            headerRow.addArrangedSubview(webfetchOpenSlot)
        }
        headerRow.addArrangedSubview(chevron)

        header.addSubview(headerRow)
        headerRow.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            headerRow.topAnchor.constraint(equalTo: header.topAnchor),
            headerRow.leadingAnchor.constraint(equalTo: header.leadingAnchor),
            headerRow.trailingAnchor.constraint(equalTo: header.trailingAnchor),
            headerRow.bottomAnchor.constraint(equalTo: header.bottomAnchor),
        ])

        if let webfetchUrl, let webfetchOpenSlot {
            var openConfig = UIButton.Configuration.plain()
            openConfig.image = UIImage(systemName: "arrow.up.right.square")
            openConfig.baseForegroundColor = .link
            openConfig.buttonSize = .mini
            openConfig.contentInsets = NSDirectionalEdgeInsets(top: 4, leading: 4, bottom: 4, trailing: 4)
            openConfig.preferredSymbolConfigurationForImage = UIImage.SymbolConfiguration(
                pointSize: UIFont.preferredFont(forTextStyle: .caption2).pointSize,
                weight: .regular
            )

            let open = UIButton(configuration: openConfig)
            open.accessibilityLabel = "Open link"
            open.addAction(
                UIAction { [weak self] _ in
                    self?.openExternalUrl(webfetchUrl)
                },
                for: .touchUpInside
            )

            header.addSubview(open)
            open.translatesAutoresizingMaskIntoConstraints = false
            NSLayoutConstraint.activate([
                open.centerXAnchor.constraint(equalTo: webfetchOpenSlot.centerXAnchor),
                open.centerYAnchor.constraint(equalTo: webfetchOpenSlot.centerYAnchor),
                open.widthAnchor.constraint(equalTo: webfetchOpenSlot.widthAnchor),
                open.heightAnchor.constraint(equalTo: webfetchOpenSlot.heightAnchor),
            ])
        }

        let body = makeToolPresentationBody(
            presentation: presentation,
            toolName: part.tool,
            toolFormat: toolFormat
        )
        let isExpanded = alwaysExpandAssistantParts
            ? !collapsedPartKeys.contains(key)
            : expandedPartKeys.contains(key)
        body.isHidden = !isExpanded
        chevron.image = UIImage(systemName: isExpanded ? "chevron.up" : "chevron.down")
        chevron.isHidden = body.arrangedSubviews.isEmpty

        header.addAction(
            UIAction { [weak self] _ in
                let willExpand = body.isHidden
                if body.arrangedSubviews.isEmpty { return }
                body.isHidden = !willExpand
                chevron.image = UIImage(systemName: willExpand ? "chevron.up" : "chevron.down")
                self?.onToggleExpandablePart?(key, willExpand)
                self?.requestLayoutUpdate()
            },
            for: .touchUpInside
        )

        stack.addArrangedSubview(header)
        stack.addArrangedSubview(body)

        container.addSubview(stack)
        stack.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: container.topAnchor),
            stack.leadingAnchor.constraint(equalTo: container.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: container.trailingAnchor),
            stack.bottomAnchor.constraint(equalTo: container.bottomAnchor),
        ])

        return container
    }

    private func toolBackgroundColor(_ state: ToolState) -> UIColor {
        switch state.name.uppercased() {
        case "PENDING":
            return UIColor.secondarySystemBackground.withAlphaComponent(0.9)
        case "RUNNING":
            return UIColor.systemIndigo.withAlphaComponent(0.10)
        case "COMPLETED":
            // Avoid clashing with diff +/- greens; keep tool cards in a neutral blue lane.
            return UIColor.systemBlue.withAlphaComponent(0.10)
        case "ERROR":
            return UIColor.systemRed.withAlphaComponent(0.12)
        default:
            return UIColor.secondarySystemBackground.withAlphaComponent(0.9)
        }
    }

    private func toolTintColor(_ state: ToolState) -> UIColor {
        switch state.name.uppercased() {
        case "PENDING":
            return .secondaryLabel
        case "RUNNING":
            return .systemIndigo
        case "COMPLETED":
            return .systemBlue
        case "ERROR":
            return .systemRed
        default:
            return .secondaryLabel
        }
    }

    private func toolIconName(_ state: ToolState) -> String {
        switch state.name.uppercased() {
        case "PENDING":
            return "hourglass"
        case "RUNNING":
            return "arrow.triangle.2.circlepath"
        case "COMPLETED":
            return "checkmark.circle.fill"
        case "ERROR":
            return "exclamationmark.triangle.fill"
        default:
            return "questionmark.circle"
        }
    }

    private func makeToolPresentationBody(presentation: ToolPresentation, toolName: String, toolFormat: String?) -> UIStackView {
        let container = UIStackView()
        container.axis = .vertical
        container.spacing = 10

        var currentFilePath: String? = nil

        for block in presentation.blocks {
            if let file = block as? ToolPresentationBlock.File {
                currentFilePath = file.path
                container.addArrangedSubview(makeToolFileRow(label: file.label, path: file.path))
                continue
            }

            if let code = block as? ToolPresentationBlock.Code {
                let renderAsMarkdown = shouldRenderMarkdownSheet(
                    toolName: toolName,
                    toolFormat: toolFormat,
                    filePath: currentFilePath,
                    sectionLabel: code.label
                )
                container.addArrangedSubview(
                    makeToolCodeSection(
                        label: code.label,
                        text: code.text,
                        filePath: currentFilePath,
                        renderAsMarkdown: renderAsMarkdown
                    )
                )
                continue
            }

            if let diff = block as? ToolPresentationBlock.Diff {
                container.addArrangedSubview(makeToolDiffSection(label: diff.label, text: diff.text))
                continue
            }

            if let params = block as? ToolPresentationBlock.KeyValues {
                container.addArrangedSubview(makeToolKeyValuesSection(label: params.label, items: params.items))
                continue
            }

            if let err = block as? ToolPresentationBlock.Error {
                container.addArrangedSubview(makeToolErrorSection(message: err.message))
                continue
            }
        }

        return container
    }

    private func makeToolFileRow(label: String, path: String) -> UIView {
        let row = UIStackView()
        row.axis = .horizontal
        row.spacing = 8
        row.alignment = .center

        let labelView = UILabel()
        labelView.font = .preferredFont(forTextStyle: .caption2)
        labelView.textColor = .secondaryLabel
        labelView.text = label
        labelView.setContentHuggingPriority(.required, for: .horizontal)

        // Keep file paths clearly tappable (link-like) and compact.
        let button = UIButton(type: .system)
        button.setTitle(path, for: .normal)
        button.setTitleColor(.link, for: .normal)
        button.titleLabel?.font = .monospacedSystemFont(ofSize: UIFont.preferredFont(forTextStyle: .footnote).pointSize, weight: .regular)
        button.titleLabel?.numberOfLines = 1
        button.contentHorizontalAlignment = .leading
        button.titleLabel?.lineBreakMode = .byTruncatingMiddle
        button.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        button.addAction(
            UIAction { [weak self] _ in
                self?.onOpenFile?(path)
            },
            for: .touchUpInside
        )

        var copyConfig = UIButton.Configuration.plain()
        copyConfig.image = UIImage(systemName: "doc.on.doc")
        copyConfig.baseForegroundColor = .secondaryLabel
        copyConfig.buttonSize = .mini
        // Give the symbol breathing room to avoid clipping.
        copyConfig.contentInsets = NSDirectionalEdgeInsets(top: 4, leading: 4, bottom: 4, trailing: 4)
        copyConfig.preferredSymbolConfigurationForImage = UIImage.SymbolConfiguration(
            pointSize: UIFont.preferredFont(forTextStyle: .caption2).pointSize,
            weight: .regular
        )

        let copy = UIButton(configuration: copyConfig)
        copy.setContentHuggingPriority(.required, for: .horizontal)
        copy.setContentCompressionResistancePriority(.required, for: .horizontal)
        copy.addAction(
            UIAction { [weak self] _ in
                UIPasteboard.general.string = path
                self?.hapticCopySuccess()
            },
            for: .touchUpInside
        )

        row.addArrangedSubview(labelView)
        row.addArrangedSubview(button)
        let spacer = UIView()
        spacer.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        spacer.setContentHuggingPriority(.defaultLow, for: .horizontal)
        row.addArrangedSubview(spacer)
        row.addArrangedSubview(copy)

        return row
    }

    private func makeToolCodeSection(label: String, text: ToolTextPreview, filePath: String?, renderAsMarkdown: Bool) -> UIView {
        let section = UIStackView()
        section.axis = .vertical
        section.spacing = 6

        section.addArrangedSubview(makeToolSectionTitle(title: label))

        section.addArrangedSubview(makeToolCodeBlock(text: text.previewText))

        if shouldOfferFullText(text) {
            section.addArrangedSubview(
                makeToolDisclosureRow(title: "View full \(label.lowercased())") { [weak self] in
                    self?.presentToolTextSheet(
                        title: label,
                        text: text.fullText,
                        filePath: filePath,
                        renderAsMarkdown: renderAsMarkdown
                    )
                }
            )
        }

        return section
    }

    private func shouldRenderMarkdownSheet(
        toolName: String,
        toolFormat: String?,
        filePath: String?,
        sectionLabel: String
    ) -> Bool {
        if let filePath, isMarkdownFile(filePath) {
            return true
        }

        // Webfetch can explicitly return markdown; in that case render output as markdown so links are clickable.
        if toolName.lowercased() == "webfetch",
           toolFormat?.lowercased() == "markdown",
           sectionLabel.lowercased() == "output" {
            return true
        }

        return false
    }

    private func isMarkdownFile(_ path: String) -> Bool {
        let lower = path.lowercased()
        return lower.hasSuffix(".md") || lower.hasSuffix(".markdown") || lower.hasSuffix(".mdown")
    }

    private func makeToolDiffSection(label: String, text: ToolTextPreview) -> UIView {
        let section = UIStackView()
        section.axis = .vertical
        section.spacing = 6

        section.addArrangedSubview(makeToolSectionTitle(title: label))

        section.addArrangedSubview(makeUnifiedDiffPreview(diffText: text.fullText, maxLines: 4) ?? makeToolCodeBlock(text: text.previewText))

        if shouldOfferFullText(text) {
            section.addArrangedSubview(
                makeToolDisclosureRow(title: "View full diff") { [weak self] in
                    self?.presentToolDiffSheet(title: label, diffText: text.fullText)
                }
            )
        }

        return section
    }

    private func makeToolSectionTitle(title: String) -> UIView {
        let label = UILabel()
        label.font = .preferredFont(forTextStyle: .caption2)
        label.textColor = .secondaryLabel
        label.text = title
        return label
    }

    private func shouldOfferFullText(_ text: ToolTextPreview) -> Bool {
        if text.isTruncated { return true }
        // Heuristic: UIKit preview clamps to 4 visual lines. Even if Kotlin didn't truncate (no extra newlines),
        // a long wrapped line can still exceed 4 visual lines, so offer full text for large payloads.
        if text.fullText.count > 140 { return true }
        return false
    }

    private func makeToolDisclosureRow(title: String, onTap: @escaping () -> Void) -> UIView {
        let control = UIControl()

        let row = UIStackView()
        row.axis = .horizontal
        row.spacing = 6
        row.alignment = .center
        row.isUserInteractionEnabled = false

        let label = UILabel()
        label.font = .preferredFont(forTextStyle: .caption1)
        label.textColor = .systemBlue
        label.text = title

        let chevron = UIImageView(image: UIImage(systemName: "chevron.right"))
        chevron.tintColor = .systemBlue
        chevron.setContentHuggingPriority(.required, for: .horizontal)

        row.addArrangedSubview(label)
        row.addArrangedSubview(UIView())
        row.addArrangedSubview(chevron)

        control.addSubview(row)
        row.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            row.topAnchor.constraint(equalTo: control.topAnchor, constant: 2),
            row.leadingAnchor.constraint(equalTo: control.leadingAnchor),
            row.trailingAnchor.constraint(equalTo: control.trailingAnchor),
            row.bottomAnchor.constraint(equalTo: control.bottomAnchor, constant: -2),
        ])

        control.addAction(UIAction { _ in onTap() }, for: .touchUpInside)
        return control
    }

    private func makeUnifiedDiffPreview(diffText: String, maxLines: Int) -> UIView? {
        let lines = UnifiedDiffParser.parse(diffText)
        guard !lines.isEmpty else { return nil }

        var firstChangeIndex: Int?
        var startIndex: Int
        if let firstChange = lines.firstIndex(where: { $0.kind == .addition || $0.kind == .deletion }) {
            firstChangeIndex = firstChange
            // Prefer starting at the nearest hunk header before the first change so the preview reads cleanly.
            if let hunkIndex = lines[0...firstChange].lastIndex(where: { $0.kind == .hunkHeader }) {
                startIndex = hunkIndex
            } else {
                startIndex = max(0, firstChange - 1)
            }
        } else if let firstHunk = lines.firstIndex(where: { $0.kind == .hunkHeader }) {
            startIndex = firstHunk
        } else {
            startIndex = 0
        }

        var endIndex = min(lines.count, startIndex + maxLines)
        var previewLines = Array(lines[startIndex..<endIndex])

        // If we have change lines, ensure the preview window actually includes at least one.
        if let firstChangeIndex, !previewLines.contains(where: { $0.kind == .addition || $0.kind == .deletion }), startIndex < firstChangeIndex {
            // Slide forward toward the first change so we don't show only context lines.
            startIndex = max(0, firstChangeIndex - max(0, maxLines - 1))
            endIndex = min(lines.count, startIndex + maxLines)
            previewLines = Array(lines[startIndex..<endIndex])
        }

        guard !previewLines.isEmpty else { return nil }
        let layout = UnifiedDiffLayout.make(lines: previewLines)

        let container = UIStackView()
        container.axis = .vertical
        container.spacing = 0
        container.layoutMargins = .zero
        container.isLayoutMarginsRelativeArrangement = true
        container.backgroundColor = UIColor.secondarySystemBackground.withAlphaComponent(0.9)
        container.layer.cornerRadius = 10
        container.layer.cornerCurve = .continuous
        container.clipsToBounds = true

        for line in previewLines {
            let row = UnifiedDiffLineRowView(layout: layout)
            row.configure(line: line)
            container.addArrangedSubview(row)
        }

        return container
    }

    private func makeToolKeyValuesSection(label: String, items: [ToolKeyValue]) -> UIView {
        let section = UIStackView()
        section.axis = .vertical
        section.spacing = 6

        let title = UILabel()
        title.font = .preferredFont(forTextStyle: .caption2)
        title.textColor = .secondaryLabel
        title.text = label
        section.addArrangedSubview(title)

        let box = UIStackView()
        box.axis = .vertical
        box.spacing = 6
        box.layoutMargins = UIEdgeInsets(top: 10, left: 10, bottom: 10, right: 10)
        box.isLayoutMarginsRelativeArrangement = true
        box.backgroundColor = UIColor.secondarySystemBackground.withAlphaComponent(0.75)
        box.layer.cornerRadius = 10
        box.layer.cornerCurve = .continuous

        for item in items {
            let row = UIStackView()
            row.axis = .horizontal
            row.spacing = 8
            row.alignment = .firstBaseline

            let key = UILabel()
            key.font = .monospacedSystemFont(ofSize: UIFont.preferredFont(forTextStyle: .caption2).pointSize, weight: .regular)
            key.textColor = .secondaryLabel
            key.text = item.key
            key.setContentHuggingPriority(.required, for: .horizontal)

            let valueView: UIView
            if let url = normalizedHttpUrl(item.value) {
                let displayUrl = displayHttpUrl(url)
                let button = UIButton(type: .system)
                button.setTitle(displayUrl, for: .normal)
                button.setTitleColor(.link, for: .normal)
                button.titleLabel?.font = .monospacedSystemFont(ofSize: UIFont.preferredFont(forTextStyle: .caption2).pointSize, weight: .regular)
                button.titleLabel?.numberOfLines = 1
                button.titleLabel?.lineBreakMode = .byTruncatingMiddle
                button.contentHorizontalAlignment = .leading
                button.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
                button.addAction(
                    UIAction { [weak self] _ in
                        self?.openExternalUrl(url)
                    },
                    for: .touchUpInside
                )
                valueView = button
            } else {
                let value = UILabel()
                value.font = .monospacedSystemFont(ofSize: UIFont.preferredFont(forTextStyle: .caption2).pointSize, weight: .regular)
                value.textColor = .label
                // Keep cards compact: show a single-line preview and offer a full sheet when needed.
                value.numberOfLines = 1
                value.lineBreakMode = .byTruncatingTail
                value.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
                value.text = item.value
                valueView = value
            }

            row.addArrangedSubview(key)
            row.addArrangedSubview(valueView)
            box.addArrangedSubview(row)
        }

        section.addArrangedSubview(box)

        let shouldOfferFull = items.count > 4 || items.contains { item in
            item.value.count > 140 || item.value.contains("\n")
        }
        if shouldOfferFull {
            let fullText = items
                .map { "\($0.key): \($0.value)" }
                .joined(separator: "\n")
            section.addArrangedSubview(
                makeToolDisclosureRow(title: "View full parameters") { [weak self] in
                    self?.presentToolTextSheet(title: label, text: fullText, filePath: nil, renderAsMarkdown: false)
                }
            )
        }

        return section
    }

    private func makeToolErrorSection(message: String) -> UIView {
        let label = UILabel()
        label.font = .preferredFont(forTextStyle: .caption1)
        label.textColor = .systemRed
        label.numberOfLines = 0
        label.text = message
        return label
    }

    private func makeToolCodeBlock(text: String) -> UIView {
        let container = UIView()
        container.backgroundColor = UIColor.secondarySystemBackground.withAlphaComponent(0.9)
        container.layer.cornerRadius = 10
        container.layer.cornerCurve = .continuous
        container.clipsToBounds = true

        let textView = IntrinsicTextView()
        textView.isEditable = false
        textView.isSelectable = true
        textView.isScrollEnabled = false
        textView.backgroundColor = .clear
        textView.isAccessibilityElement = false
        textView.delegate = self
        textView.dataDetectorTypes = []
        textView.textContainerInset = UIEdgeInsets(top: 10, left: 10, bottom: 10, right: 10)
        textView.textContainer.lineFragmentPadding = 0
        textView.textContainer.maximumNumberOfLines = 4
        textView.textContainer.lineBreakMode = .byTruncatingTail
        textView.font = .monospacedSystemFont(ofSize: UIFont.preferredFont(forTextStyle: .footnote).pointSize, weight: .regular)
        textView.textColor = .label
        textView.setContentCompressionResistancePriority(.required, for: .vertical)
        textView.text = text

        container.addSubview(textView)
        textView.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            textView.topAnchor.constraint(equalTo: container.topAnchor),
            textView.leadingAnchor.constraint(equalTo: container.leadingAnchor),
            textView.trailingAnchor.constraint(equalTo: container.trailingAnchor),
            textView.bottomAnchor.constraint(equalTo: container.bottomAnchor),
        ])

        return container
    }

    private func hapticCopySuccess() {
        let generator = UINotificationFeedbackGenerator()
        generator.notificationOccurred(.success)
    }

    private func presentToolTextSheet(title: String, text: String, filePath: String?, renderAsMarkdown: Bool) {
        guard let presenter = nearestViewController() else { return }
        let vc = ToolTextSheetViewController(
            title: title,
            text: text,
            filePath: filePath,
            renderAsMarkdown: renderAsMarkdown,
            onOpenFile: onOpenFile
        )
        let nav = UINavigationController(rootViewController: vc)
        if let sheet = nav.sheetPresentationController {
            sheet.detents = [.large()]
            sheet.prefersGrabberVisible = true
            sheet.selectedDetentIdentifier = .large
        }
        presenter.present(nav, animated: true)
    }

    private func parseToolInputJson(_ raw: String?) -> [String: Any]? {
        guard let raw, let data = raw.data(using: .utf8) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    private func normalizedHttpUrl(_ raw: String) -> String? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.hasPrefix("http://") || trimmed.hasPrefix("https://") else { return nil }
        guard URL(string: trimmed) != nil else { return nil }
        return trimmed
    }

    private func displayHttpUrl(_ raw: String) -> String {
        var value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        value = value.replacingOccurrences(of: "^https?://", with: "", options: [.regularExpression, .caseInsensitive])
        value = value.replacingOccurrences(of: "^www\\.", with: "", options: [.regularExpression, .caseInsensitive])
        while value.hasSuffix("/") {
            value.removeLast()
        }
        return value
    }

    private func openExternalUrl(_ urlString: String) {
        guard let presenter = nearestViewController() else { return }
        guard let url = URL(string: urlString) else { return }
        let safari = SFSafariViewController(url: url)
        presenter.present(safari, animated: true)
    }

    private func presentToolDiffSheet(title: String, diffText: String) {
        guard let presenter = nearestViewController() else { return }
        let vc = ToolDiffSheetViewController(title: title, diffText: diffText)
        let nav = UINavigationController(rootViewController: vc)
        if let sheet = nav.sheetPresentationController {
            sheet.detents = [.large()]
            sheet.prefersGrabberVisible = true
            sheet.selectedDetentIdentifier = .large
        }
        presenter.present(nav, animated: true)
    }

    private func nearestViewController() -> UIViewController? {
        sequence(first: self as UIResponder?, next: { $0?.next })
            .first { $0 is UIViewController } as? UIViewController
    }

    private func makeFilePartView(_ part: FilePart) -> UIView {
        let container = UIView()
        container.backgroundColor = UIColor.secondarySystemBackground.withAlphaComponent(0.9)
        container.layer.cornerRadius = 12
        container.layer.cornerCurve = .continuous

        let row = UIStackView()
        row.axis = .horizontal
        row.spacing = 8
        row.alignment = .center
        row.layoutMargins = UIEdgeInsets(top: 8, left: 10, bottom: 8, right: 10)
        row.isLayoutMarginsRelativeArrangement = true

        let icon = UIImageView(image: UIImage(systemName: "paperclip"))
        icon.tintColor = .secondaryLabel
        icon.setContentHuggingPriority(.required, for: .horizontal)

        let label = UILabel()
        label.font = .preferredFont(forTextStyle: .callout)
        label.textColor = .secondaryLabel
        label.numberOfLines = 0
        label.text = part.filename ?? "Unnamed file"

        row.addArrangedSubview(icon)
        row.addArrangedSubview(label)
        row.addArrangedSubview(UIView())

        container.addSubview(row)
        row.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            row.topAnchor.constraint(equalTo: container.topAnchor),
            row.leadingAnchor.constraint(equalTo: container.leadingAnchor),
            row.trailingAnchor.constraint(equalTo: container.trailingAnchor),
            row.bottomAnchor.constraint(equalTo: container.bottomAnchor),
        ])

        return container
    }

    private func makePatchPartView(_ part: PatchPart) -> UIView {
        let container = UIView()
        container.backgroundColor = UIColor.systemGreen.withAlphaComponent(0.10)
        container.layer.cornerRadius = 12
        container.layer.cornerCurve = .continuous

        let stack = UIStackView()
        stack.axis = .vertical
        stack.spacing = 6
        stack.layoutMargins = UIEdgeInsets(top: 8, left: 10, bottom: 8, right: 10)
        stack.isLayoutMarginsRelativeArrangement = true

        let headerRow = UIStackView()
        headerRow.axis = .horizontal
        headerRow.spacing = 6
        headerRow.alignment = .center

        let icon = UIImageView(image: UIImage(systemName: "doc.text"))
        icon.tintColor = .systemGreen
        icon.setContentHuggingPriority(.required, for: .horizontal)

        let title = UILabel()
        title.font = .preferredFont(forTextStyle: .caption1)
        title.textColor = .systemGreen
        title.text = "Patch (\(part.files.count) file\(part.files.count == 1 ? "" : "s"))"

        headerRow.addArrangedSubview(icon)
        headerRow.addArrangedSubview(title)
        headerRow.addArrangedSubview(UIView())

        stack.addArrangedSubview(headerRow)

        for file in part.files.prefix(3) {
            let button = UIButton(type: .system)
            button.setTitle(file, for: .normal)
            button.titleLabel?.font = .monospacedSystemFont(ofSize: UIFont.preferredFont(forTextStyle: .callout).pointSize, weight: .regular)
            button.contentHorizontalAlignment = .leading
            button.addAction(
                UIAction { [weak self] _ in
                    self?.onOpenFile?(file)
                },
                for: .touchUpInside
            )
            stack.addArrangedSubview(button)
        }

        if part.files.count > 3 {
            let more = UILabel()
            more.font = .preferredFont(forTextStyle: .caption1)
            more.textColor = .secondaryLabel
            more.text = "… and \(part.files.count - 3) more"
            stack.addArrangedSubview(more)
        }

        container.addSubview(stack)
        stack.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: container.topAnchor),
            stack.leadingAnchor.constraint(equalTo: container.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: container.trailingAnchor),
            stack.bottomAnchor.constraint(equalTo: container.bottomAnchor),
        ])

        return container
    }

    private func requestLayoutUpdate() {
        guard let collectionView = sequence(first: superview, next: { $0?.superview }).first(where: { $0 is UICollectionView }) as? UICollectionView else {
            setNeedsLayout()
            return
        }

        UIView.performWithoutAnimation {
            collectionView.collectionViewLayout.invalidateLayout()
            collectionView.performBatchUpdates(nil)
        }
    }

    private func buildAttributedText(text: String, isSecondary: Bool) -> NSAttributedString {
        let linkified = MarkdownInterop.shared.linkify(text: text)
        let parsed = MarkdownLinkParser.parse(linkified)
        let fullRange = NSRange(location: 0, length: parsed.length)

        parsed.addAttribute(
            NSAttributedString.Key.font,
            value: UIFont.preferredFont(forTextStyle: .body),
            range: fullRange
        )
        parsed.addAttribute(
            NSAttributedString.Key.foregroundColor,
            value: isSecondary ? UIColor.secondaryLabel : UIColor.label,
            range: fullRange
        )

        parsed.enumerateAttribute(NSAttributedString.Key.link, in: fullRange) { value, range, _ in
            guard value != nil else { return }
            parsed.addAttribute(NSAttributedString.Key.foregroundColor, value: UIColor.systemBlue, range: range)
            parsed.addAttribute(NSAttributedString.Key.underlineStyle, value: NSUnderlineStyle.single.rawValue, range: range)
        }

        return parsed
    }

    private func configureAccessibility(
        message: Message,
        assistantVisibility: AssistantResponsePartVisibility,
        isUser: Bool
    ) {
        isAccessibilityElement = true
        accessibilityTraits = [.staticText]
        accessibilityLabel = isUser ? "You" : "Assistant"
        accessibilityHint = isUser ? "Actions available" : nil
        accessibilityValue = accessibilityValueText(
            message: message,
            assistantVisibility: assistantVisibility,
            isUser: isUser
        )

        if isUser {
            accessibilityCustomActions = [
                UIAccessibilityCustomAction(name: "Message actions", target: self, selector: #selector(didTapActions)),
            ]
        } else {
            accessibilityCustomActions = nil
        }
    }

    private func accessibilityValueText(
        message: Message,
        assistantVisibility: AssistantResponsePartVisibility,
        isUser: Bool
    ) -> String {
        let parts: [MessagePart]
        if isUser {
            parts = message.parts.filter { part in
                part is TextPart || part is FilePart
            }
        } else {
            parts = MessagePartVisibilityKt.filterVisibleParts(
                parts: message.parts,
                visibility: assistantVisibility
            )
        }

        var lines: [String] = []
        for part in parts {
            if let text = part as? TextPart, !text.text.isEmpty {
                lines.append(text.text)
                continue
            }
            if let reasoning = part as? ReasoningPart, !reasoning.text.isEmpty {
                if !isUser, !assistantVisibility.showReasoning { continue }
                lines.append("Thinking")
                lines.append(reasoning.text)
                continue
            }
            if let tool = part as? ToolPart {
                if !isUser, !assistantVisibility.showTools { continue }
                lines.append("Tool \(tool.tool)")
                continue
            }
            if let file = part as? FilePart {
                lines.append("File \(file.filename ?? "Unnamed file")")
                continue
            }
            if let patch = part as? PatchPart {
                if !isUser, !assistantVisibility.showPatches { continue }
                lines.append("Patch (\(patch.files.count) files)")
                continue
            }
        }

        let text = lines.joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty { return "Message" }
        if text.count <= 800 { return text }
        let idx = text.index(text.startIndex, offsetBy: 800)
        return String(text[..<idx]) + "…"
    }
}

extension ChatMessageCell: UITextViewDelegate {
    func textView(
        _ textView: UITextView,
        shouldInteractWith url: URL,
        in characterRange: NSRange,
        interaction: UITextItemInteraction
    ) -> Bool {
        // Reserve long-press for text selection. Only allow link interaction on a simple tap.
        if interaction != .invokeDefaultAction {
            return false
        }

        let absolute = url.absoluteString
        if absolute.hasPrefix("oc-pocket-file:") {
            let encoded = String(absolute.dropFirst("oc-pocket-file:".count))
            let decoded = encoded.removingPercentEncoding ?? encoded
            onOpenFile?(decoded)
            return false
        }
        if url.scheme?.lowercased() == "oc-pocket" {
            return false
        }
        return true
    }
}
