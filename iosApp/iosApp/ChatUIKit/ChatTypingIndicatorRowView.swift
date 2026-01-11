import UIKit

@MainActor
final class ChatTypingIndicatorRowView: UIView {
    private let baseLabel = UILabel()
    private let shimmerLabel = UILabel()

    private let shimmerMaskLayer = CAGradientLayer()
    private let shimmerAnimationKey = "typingShimmer"

    override init(frame: CGRect) {
        super.init(frame: frame)

        isAccessibilityElement = true
        accessibilityLabel = "Assistant is typing"

        baseLabel.translatesAutoresizingMaskIntoConstraints = false
        shimmerLabel.translatesAutoresizingMaskIntoConstraints = false

        baseLabel.text = "Assistant is typing..."
        shimmerLabel.text = "Assistant is typing..."

        baseLabel.font = .preferredFont(forTextStyle: .footnote)
        shimmerLabel.font = .preferredFont(forTextStyle: .footnote)
        baseLabel.adjustsFontForContentSizeCategory = true
        shimmerLabel.adjustsFontForContentSizeCategory = true

        baseLabel.textColor = .secondaryLabel
        shimmerLabel.textColor = UIColor.label.withAlphaComponent(0.80)

        addSubview(baseLabel)
        addSubview(shimmerLabel)

        NSLayoutConstraint.activate([
            baseLabel.topAnchor.constraint(equalTo: topAnchor),
            baseLabel.bottomAnchor.constraint(equalTo: bottomAnchor),
            baseLabel.leadingAnchor.constraint(equalTo: leadingAnchor),
            baseLabel.trailingAnchor.constraint(equalTo: trailingAnchor),

            shimmerLabel.topAnchor.constraint(equalTo: topAnchor),
            shimmerLabel.bottomAnchor.constraint(equalTo: bottomAnchor),
            shimmerLabel.leadingAnchor.constraint(equalTo: leadingAnchor),
            shimmerLabel.trailingAnchor.constraint(equalTo: trailingAnchor),
        ])

        shimmerMaskLayer.startPoint = CGPoint(x: 0.0, y: 0.5)
        shimmerMaskLayer.endPoint = CGPoint(x: 1.0, y: 0.5)
        shimmerMaskLayer.colors = [
            UIColor.white.withAlphaComponent(0.0).cgColor,
            UIColor.white.withAlphaComponent(1.0).cgColor,
            UIColor.white.withAlphaComponent(0.0).cgColor,
        ]
        shimmerMaskLayer.locations = [-1.0, -0.5, 0.0]
        shimmerLabel.layer.mask = shimmerMaskLayer
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        shimmerMaskLayer.frame = shimmerLabel.bounds
        // Ensure shimmer starts as soon as we have a real layout (initial window attach can happen
        // before Auto Layout has produced a non-zero bounds size).
        startShimmerIfNeeded()
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window != nil {
            startShimmerIfNeeded()
        } else {
            stopShimmer()
        }
    }

    func startShimmerIfNeeded() {
        guard shimmerMaskLayer.animation(forKey: shimmerAnimationKey) == nil else { return }
        guard shimmerLabel.bounds.width > 0 else { return }

        let animation = CABasicAnimation(keyPath: "locations")
        animation.fromValue = [-1.0, -0.5, 0.0]
        animation.toValue = [1.0, 1.5, 2.0]
        animation.duration = 1.10
        animation.repeatCount = .infinity
        animation.timingFunction = CAMediaTimingFunction(name: .linear)

        shimmerMaskLayer.add(animation, forKey: shimmerAnimationKey)
    }

    func stopShimmer() {
        shimmerMaskLayer.removeAnimation(forKey: shimmerAnimationKey)
    }
}
