import UIKit

final class IntrinsicTextView: UITextView {
    private var lastWidth: CGFloat = 0

    override var text: String! {
        didSet {
            guard oldValue != text else { return }
            invalidateIntrinsicContentSize()
        }
    }

    override var attributedText: NSAttributedString! {
        didSet {
            guard oldValue !== attributedText else { return }
            invalidateIntrinsicContentSize()
        }
    }

    var layoutWidthHint: CGFloat? {
        didSet {
            guard oldValue != layoutWidthHint else { return }
            invalidateIntrinsicContentSize()
        }
    }

    override var contentSize: CGSize {
        didSet {
            invalidateIntrinsicContentSize()
        }
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        if abs(bounds.width - lastWidth) > 0.5 {
            lastWidth = bounds.width
            invalidateIntrinsicContentSize()
        }
    }

    override var intrinsicContentSize: CGSize {
        let fallbackWidth = layoutWidthHint ?? (bounds.width > 0 ? bounds.width : UIScreen.main.bounds.width)
        let targetWidth = max(1, fallbackWidth)
        let size = sizeThatFits(CGSize(width: targetWidth, height: .greatestFiniteMagnitude))
        return CGSize(width: UIView.noIntrinsicMetric, height: size.height)
    }

    override func systemLayoutSizeFitting(
        _ targetSize: CGSize,
        withHorizontalFittingPriority horizontalFittingPriority: UILayoutPriority,
        verticalFittingPriority: UILayoutPriority
    ) -> CGSize {
        let fallbackWidth = layoutWidthHint ?? (bounds.width > 0 ? bounds.width : UIScreen.main.bounds.width)
        let width = targetSize.width > 0 ? targetSize.width : max(1, fallbackWidth)
        let size = sizeThatFits(CGSize(width: width, height: .greatestFiniteMagnitude))
        return CGSize(width: width, height: size.height)
    }
}
