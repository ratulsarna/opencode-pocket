import XCTest
 import OpenCodePocket

final class ChatAutoScrollPolicyTests: XCTestCase {
    func test_isPinnedToBottom_trueWhenWithinThreshold() {
        var policy = ChatAutoScrollPolicy()
        policy.pinnedThresholdPoints = 72

        let metrics = ChatAutoScrollPolicy.ScrollMetrics(
            contentOffsetY: 900,
            viewportHeight: 200,
            contentHeight: 1150
        )
        XCTAssertTrue(policy.isPinnedToBottom(metrics: metrics))
    }

    func test_isPinnedToBottom_falseWhenFarFromBottom() {
        var policy = ChatAutoScrollPolicy()
        policy.pinnedThresholdPoints = 72

        let metrics = ChatAutoScrollPolicy.ScrollMetrics(
            contentOffsetY: 400,
            viewportHeight: 200,
            contentHeight: 1150
        )
        XCTAssertFalse(policy.isPinnedToBottom(metrics: metrics))
    }

    func test_decide_initialLoad_scrollsToBottom_withoutAnimation_consumesInitialScroll() {
        let policy = ChatAutoScrollPolicy()
        let decision = policy.decide(updateKind: .initialLoad, isPinnedToBottom: false, didInitialScroll: false)

        XCTAssertEqual(decision.shouldScrollToBottom, true)
        XCTAssertEqual(decision.animateScroll, false)
        XCTAssertEqual(decision.shouldShowScrollToBottomButton, false)
        XCTAssertEqual(decision.didConsumeInitialScroll, true)
    }

    func test_decide_messagesAppended_whenPinned_scrollsAnimated() {
        let policy = ChatAutoScrollPolicy()
        let decision = policy.decide(updateKind: .messagesAppended, isPinnedToBottom: true, didInitialScroll: true)

        XCTAssertEqual(decision.shouldScrollToBottom, true)
        XCTAssertEqual(decision.animateScroll, true)
        XCTAssertEqual(decision.shouldShowScrollToBottomButton, false)
        XCTAssertEqual(decision.didConsumeInitialScroll, false)
    }

    func test_decide_messageContentUpdated_whenPinned_scrollsNotAnimated() {
        let policy = ChatAutoScrollPolicy()
        let decision = policy.decide(updateKind: .messageContentUpdated, isPinnedToBottom: true, didInitialScroll: true)

        XCTAssertEqual(decision.shouldScrollToBottom, true)
        XCTAssertEqual(decision.animateScroll, false)
        XCTAssertEqual(decision.shouldShowScrollToBottomButton, false)
        XCTAssertEqual(decision.didConsumeInitialScroll, false)
    }

    func test_decide_layoutOnly_whenPinned_scrollsNotAnimated() {
        let policy = ChatAutoScrollPolicy()
        let decision = policy.decide(updateKind: .layoutOnly, isPinnedToBottom: true, didInitialScroll: true)

        XCTAssertEqual(decision.shouldScrollToBottom, true)
        XCTAssertEqual(decision.animateScroll, false)
        XCTAssertEqual(decision.shouldShowScrollToBottomButton, false)
        XCTAssertEqual(decision.didConsumeInitialScroll, false)
    }

    func test_decide_whenNotPinned_neverAutoScroll_showsButtonForUpdates() {
        let policy = ChatAutoScrollPolicy()

        XCTAssertEqual(
            policy.decide(updateKind: .messagesAppended, isPinnedToBottom: false, didInitialScroll: true).shouldScrollToBottom,
            false
        )
        XCTAssertEqual(
            policy.decide(updateKind: .messagesAppended, isPinnedToBottom: false, didInitialScroll: true).shouldShowScrollToBottomButton,
            true
        )

        XCTAssertEqual(
            policy.decide(updateKind: .messageContentUpdated, isPinnedToBottom: false, didInitialScroll: true).shouldScrollToBottom,
            false
        )
        XCTAssertEqual(
            policy.decide(updateKind: .messageContentUpdated, isPinnedToBottom: false, didInitialScroll: true).shouldShowScrollToBottomButton,
            true
        )

        XCTAssertEqual(
            policy.decide(updateKind: .layoutOnly, isPinnedToBottom: false, didInitialScroll: true).shouldScrollToBottom,
            false
        )
        XCTAssertEqual(
            policy.decide(updateKind: .layoutOnly, isPinnedToBottom: false, didInitialScroll: true).shouldShowScrollToBottomButton,
            true
        )
    }
}
