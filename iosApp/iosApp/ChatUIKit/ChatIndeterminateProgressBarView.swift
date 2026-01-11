import UIKit

@MainActor
final class ChatIndeterminateProgressBarView: UIView {
    var barColor: UIColor = .systemBlue {
        didSet { barLayer.backgroundColor = barColor.cgColor }
    }

    var barHeight: CGFloat = 2 {
        didSet { invalidateIntrinsicContentSize() }
    }

    var segmentWidthFraction: CGFloat = 0.28 {
        didSet { setNeedsLayout() }
    }

    var animationDuration: CFTimeInterval = 1.15

    private let barLayer = CALayer()
    private var isAnimating: Bool = false
    private var lastBoundsForAnimation: CGRect = .zero

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        backgroundColor = .clear

        barLayer.backgroundColor = barColor.cgColor
        layer.addSublayer(barLayer)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override var intrinsicContentSize: CGSize {
        CGSize(width: UIView.noIntrinsicMetric, height: barHeight)
    }

    override func layoutSubviews() {
        super.layoutSubviews()

        if UIAccessibility.isReduceMotionEnabled {
            barLayer.removeAnimation(forKey: "indeterminate")
            lastBoundsForAnimation = .zero
            barLayer.frame = bounds
            barLayer.cornerRadius = bounds.height / 2
            return
        }

        layoutBarLayer()
        if isAnimating, bounds != lastBoundsForAnimation {
            restartAnimation()
        }
    }

    func startAnimating() {
        guard !UIAccessibility.isReduceMotionEnabled else {
            stopAnimating()
            barLayer.frame = bounds
            barLayer.cornerRadius = bounds.height / 2
            return
        }

        guard !isAnimating else { return }
        isAnimating = true
        restartAnimation()
    }

    func stopAnimating() {
        isAnimating = false
        barLayer.removeAnimation(forKey: "indeterminate")
        lastBoundsForAnimation = .zero
    }

    private func restartAnimation() {
        barLayer.removeAnimation(forKey: "indeterminate")
        layoutBarLayer()
        lastBoundsForAnimation = bounds

        let segmentWidth = barLayer.bounds.width
        let startX: CGFloat = -segmentWidth / 2
        let endX: CGFloat = bounds.width + segmentWidth / 2
        let midY: CGFloat = bounds.midY

        barLayer.position = CGPoint(x: startX, y: midY)

        let animation = CABasicAnimation(keyPath: "position.x")
        animation.fromValue = startX
        animation.toValue = endX
        animation.duration = animationDuration
        animation.repeatCount = .infinity
        animation.timingFunction = CAMediaTimingFunction(name: .easeInEaseOut)
        animation.isRemovedOnCompletion = false

        barLayer.add(animation, forKey: "indeterminate")
    }

    private func layoutBarLayer() {
        let height = max(1, bounds.height)
        let fraction = max(0.12, min(segmentWidthFraction, 0.65))
        let segmentWidth = max(24, bounds.width * fraction)
        barLayer.bounds = CGRect(x: 0, y: 0, width: segmentWidth, height: height)
        barLayer.cornerRadius = height / 2
    }
}
