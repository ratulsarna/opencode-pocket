import Foundation

struct ChatAutoScrollPolicy {
    enum UpdateKind {
        case initialLoad
        case messagesAppended
        case messageContentUpdated
        case layoutOnly
    }

    struct ScrollMetrics: Equatable {
        var contentOffsetY: Double
        var viewportHeight: Double
        var contentHeight: Double
    }

    struct Decision: Equatable {
        var shouldScrollToBottom: Bool
        var animateScroll: Bool
        var shouldShowScrollToBottomButton: Bool
        var didConsumeInitialScroll: Bool
    }

    var pinnedThresholdPoints: Double = 72

    func isPinnedToBottom(metrics: ScrollMetrics) -> Bool {
        guard metrics.contentHeight > 0, metrics.viewportHeight > 0 else { return true }
        return metrics.contentOffsetY + metrics.viewportHeight >= metrics.contentHeight - pinnedThresholdPoints
    }

    func decide(
        updateKind: UpdateKind,
        isPinnedToBottom: Bool,
        didInitialScroll: Bool
    ) -> Decision {
        switch updateKind {
        case .initialLoad:
            if didInitialScroll {
                return Decision(
                    shouldScrollToBottom: false,
                    animateScroll: false,
                    shouldShowScrollToBottomButton: !isPinnedToBottom,
                    didConsumeInitialScroll: false
                )
            }
            return Decision(
                shouldScrollToBottom: true,
                animateScroll: false,
                shouldShowScrollToBottomButton: false,
                didConsumeInitialScroll: true
            )

        case .messagesAppended:
            if isPinnedToBottom {
                return Decision(
                    shouldScrollToBottom: true,
                    animateScroll: true,
                    shouldShowScrollToBottomButton: false,
                    didConsumeInitialScroll: false
                )
            }
            return Decision(
                shouldScrollToBottom: false,
                animateScroll: false,
                shouldShowScrollToBottomButton: true,
                didConsumeInitialScroll: false
            )

        case .messageContentUpdated:
            if isPinnedToBottom {
                return Decision(
                    shouldScrollToBottom: true,
                    animateScroll: false,
                    shouldShowScrollToBottomButton: false,
                    didConsumeInitialScroll: false
                )
            }
            return Decision(
                shouldScrollToBottom: false,
                animateScroll: false,
                shouldShowScrollToBottomButton: true,
                didConsumeInitialScroll: false
            )

        case .layoutOnly:
            if isPinnedToBottom {
                return Decision(
                    shouldScrollToBottom: true,
                    animateScroll: false,
                    shouldShowScrollToBottomButton: false,
                    didConsumeInitialScroll: false
                )
            }
            return Decision(
                shouldScrollToBottom: false,
                animateScroll: false,
                shouldShowScrollToBottomButton: true,
                didConsumeInitialScroll: false
            )
        }
    }
}
