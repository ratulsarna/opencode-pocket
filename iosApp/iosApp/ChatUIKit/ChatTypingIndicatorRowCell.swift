import UIKit

@MainActor
final class ChatTypingIndicatorRowCell: UICollectionViewCell {
    static let reuseIdentifier = "ChatTypingIndicatorRowCell"

    private let typingView = ChatTypingIndicatorRowView()

    override init(frame: CGRect) {
        super.init(frame: frame)

        backgroundColor = .clear
        contentView.backgroundColor = .clear

        typingView.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(typingView)

        NSLayoutConstraint.activate([
            // Extra vertical padding so the row doesn't feel glued to the composer.
            typingView.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 6),
            typingView.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -14),
            typingView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 16),
            typingView.trailingAnchor.constraint(lessThanOrEqualTo: contentView.trailingAnchor, constant: -16),
        ])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func prepareForReuse() {
        super.prepareForReuse()
        typingView.startShimmerIfNeeded()
    }
}
