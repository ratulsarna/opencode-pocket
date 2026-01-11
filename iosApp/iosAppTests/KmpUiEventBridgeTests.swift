import Foundation
import XCTest
import ComposeApp
 import OpenCodePocket

@MainActor
final class KmpUiEventBridgeTests: XCTestCase {
    private func waitUntil(
        _ condition: @escaping () -> Bool,
        timeoutSeconds: Double = 2.0
    ) async {
        let deadline = Date().addingTimeInterval(timeoutSeconds)
        while !condition() {
            if Date() >= deadline {
                XCTFail("Timed out waiting for condition")
                return
            }
            try? await Task.sleep(nanoseconds: 20_000_000)
        }
    }

    func test_startCreatesSingleSubscription() async {
        let source = IosEventBridgeTestSource()
        let bridge = KmpUiEventBridge<String>()

        bridge.start(flow: source.events) { _ in }
        bridge.start(flow: source.events) { _ in }

        await waitUntil({ source.subscriptionCount.value.intValue == 1 })
        bridge.stop()
        await waitUntil({ source.subscriptionCount.value.intValue == 0 })
    }

    func test_startTwiceDoesNotDuplicateDelivery() async {
        let source = IosEventBridgeTestSource()
        let bridge = KmpUiEventBridge<String>()
        var received: [String] = []

        bridge.start(flow: source.events) { value in
            received.append(value)
        }
        bridge.start(flow: source.events) { value in
            received.append(value)
        }

        await waitUntil({ source.subscriptionCount.value.intValue == 1 })
        _ = source.tryEmit(value: "A")
        await waitUntil({ received == ["A"] })

        bridge.stop()
        await waitUntil({ source.subscriptionCount.value.intValue == 0 })
    }

    func test_stopCancelsSubscription() async {
        let source = IosEventBridgeTestSource()
        let bridge = KmpUiEventBridge<String>()

        bridge.start(flow: source.events) { _ in }
        await waitUntil({ source.subscriptionCount.value.intValue == 1 })

        bridge.stop()
        await waitUntil({ source.subscriptionCount.value.intValue == 0 })
    }

    func test_stopIsIdempotent() async {
        let source = IosEventBridgeTestSource()
        let bridge = KmpUiEventBridge<String>()

        bridge.start(flow: source.events) { _ in }
        await waitUntil({ source.subscriptionCount.value.intValue == 1 })

        bridge.stop()
        bridge.stop()
        await waitUntil({ source.subscriptionCount.value.intValue == 0 })
    }

    func test_deinitCancelsSubscription() async {
        let source = IosEventBridgeTestSource()
        var bridge: KmpUiEventBridge<String>? = KmpUiEventBridge<String>()

        bridge?.start(flow: source.events) { _ in }
        await waitUntil({ source.subscriptionCount.value.intValue == 1 })

        bridge = nil
        await waitUntil({ source.subscriptionCount.value.intValue == 0 })
    }

    func test_eventsDeliveredExactlyOnceInOrder() async {
        let source = IosEventBridgeTestSource()
        let bridge = KmpUiEventBridge<String>()
        var received: [String] = []

        bridge.start(flow: source.events) { value in
            received.append(value)
        }
        await waitUntil({ source.subscriptionCount.value.intValue == 1 })

        for value in ["A", "B", "C"] {
            _ = source.tryEmit(value: value)
        }

        await waitUntil({ received == ["A", "B", "C"] })

        bridge.stop()
        await waitUntil({ source.subscriptionCount.value.intValue == 0 })
    }

    func test_handlerRunsOnMainActor() async {
        let source = IosEventBridgeTestSource()
        let bridge = KmpUiEventBridge<String>()
        var handlerWasMain: Bool?

        bridge.start(flow: source.events) { _ in
            handlerWasMain = Thread.isMainThread
        }
        await waitUntil({ source.subscriptionCount.value.intValue == 1 })

        _ = source.tryEmit(value: "A")
        await waitUntil({ handlerWasMain != nil })

        XCTAssertEqual(handlerWasMain, true)

        bridge.stop()
        await waitUntil({ source.subscriptionCount.value.intValue == 0 })
    }
}
