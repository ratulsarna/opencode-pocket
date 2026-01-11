import UIKit

final class ChatConnectionBannerView: UIView {
    private let stack = UIStackView()
    private let spinner = UIActivityIndicatorView(style: .medium)
    private let label = UILabel()

    override init(frame: CGRect) {
        super.init(frame: frame)
        setUp()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    private func setUp() {
        backgroundColor = .secondarySystemBackground

        spinner.startAnimating()

        label.text = "Reconnecting…"
        label.font = .preferredFont(forTextStyle: .subheadline)
        label.textColor = .secondaryLabel

        stack.axis = .horizontal
        stack.spacing = 10
        stack.alignment = .center
        stack.layoutMargins = UIEdgeInsets(top: 8, left: 12, bottom: 8, right: 12)
        stack.isLayoutMarginsRelativeArrangement = true

        stack.addArrangedSubview(spinner)
        stack.addArrangedSubview(label)
        stack.addArrangedSubview(UIView())

        addSubview(stack)
        stack.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: topAnchor),
            stack.leadingAnchor.constraint(equalTo: leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: trailingAnchor),
            stack.bottomAnchor.constraint(equalTo: bottomAnchor),
        ])
    }
}

final class ChatErrorBannerView: UIView {
    private let stack = UIStackView()
    private let messageLabel = UILabel()
    private let buttonsRow = UIStackView()
    private let dismissButton = UIButton(type: .system)
    private let retryButton = UIButton(type: .system)
    private let revertButton = UIButton(type: .system)

    private var onDismiss: (() -> Void)?
    private var onRetry: (() -> Void)?
    private var onRevert: (() -> Void)?

    override init(frame: CGRect) {
        super.init(frame: frame)
        setUp()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    private func setUp() {
        backgroundColor = UIColor.systemRed.withAlphaComponent(0.14)

        messageLabel.font = .preferredFont(forTextStyle: .subheadline)
        messageLabel.textColor = .label
        messageLabel.numberOfLines = 0

        buttonsRow.axis = .horizontal
        buttonsRow.spacing = 10
        buttonsRow.alignment = .center

        dismissButton.setTitle("Dismiss", for: .normal)
        dismissButton.addAction(UIAction { [weak self] _ in self?.onDismiss?() }, for: .touchUpInside)

        retryButton.setTitle("Retry", for: .normal)
        retryButton.addAction(UIAction { [weak self] _ in self?.onRetry?() }, for: .touchUpInside)

        revertButton.setTitle("Revert", for: .normal)
        revertButton.tintColor = .systemRed
        revertButton.addAction(UIAction { [weak self] _ in self?.onRevert?() }, for: .touchUpInside)

        buttonsRow.addArrangedSubview(dismissButton)
        buttonsRow.addArrangedSubview(retryButton)
        buttonsRow.addArrangedSubview(revertButton)
        buttonsRow.addArrangedSubview(UIView())

        stack.axis = .vertical
        stack.spacing = 10
        stack.layoutMargins = UIEdgeInsets(top: 10, left: 12, bottom: 10, right: 12)
        stack.isLayoutMarginsRelativeArrangement = true
        stack.addArrangedSubview(messageLabel)
        stack.addArrangedSubview(buttonsRow)

        addSubview(stack)
        stack.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: topAnchor),
            stack.leadingAnchor.constraint(equalTo: leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: trailingAnchor),
            stack.bottomAnchor.constraint(equalTo: bottomAnchor),
        ])
    }

    func configure(
        message: String,
        showRevert: Bool,
        onDismiss: @escaping () -> Void,
        onRetry: @escaping () -> Void,
        onRevert: @escaping () -> Void
    ) {
        messageLabel.text = message
        revertButton.isHidden = !showRevert

        self.onDismiss = onDismiss
        self.onRetry = onRetry
        self.onRevert = onRevert
    }
}

final class ChatAttachmentErrorSnackbarView: UIView {
    var message: String = "" {
        didSet { label.text = message }
    }

    var onDismiss: (() -> Void)?

    private let backgroundView = UIVisualEffectView(effect: UIBlurEffect(style: .systemUltraThinMaterial))
    private let stack = UIStackView()
    private let label = UILabel()
    private let closeButton = UIButton(type: .system)

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

        backgroundView.layer.cornerRadius = 14
        backgroundView.layer.cornerCurve = .continuous
        backgroundView.clipsToBounds = true

        stack.axis = .horizontal
        stack.spacing = 10
        stack.alignment = .top
        stack.layoutMargins = UIEdgeInsets(top: 10, left: 12, bottom: 10, right: 12)
        stack.isLayoutMarginsRelativeArrangement = true

        label.font = .preferredFont(forTextStyle: .subheadline)
        label.textColor = .label
        label.numberOfLines = 0

        closeButton.setImage(UIImage(systemName: "xmark.circle.fill"), for: .normal)
        closeButton.tintColor = .secondaryLabel
        closeButton.setContentHuggingPriority(.required, for: .horizontal)
        closeButton.addAction(UIAction { [weak self] _ in self?.onDismiss?() }, for: .touchUpInside)

        stack.addArrangedSubview(label)
        stack.addArrangedSubview(closeButton)

        addSubview(backgroundView)
        backgroundView.contentView.addSubview(stack)

        backgroundView.translatesAutoresizingMaskIntoConstraints = false
        stack.translatesAutoresizingMaskIntoConstraints = false

        NSLayoutConstraint.activate([
            backgroundView.topAnchor.constraint(equalTo: topAnchor, constant: 10),
            backgroundView.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 12),
            backgroundView.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -12),
            backgroundView.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -10),

            stack.topAnchor.constraint(equalTo: backgroundView.contentView.topAnchor),
            stack.leadingAnchor.constraint(equalTo: backgroundView.contentView.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: backgroundView.contentView.trailingAnchor),
            stack.bottomAnchor.constraint(equalTo: backgroundView.contentView.bottomAnchor),
        ])
    }
}

