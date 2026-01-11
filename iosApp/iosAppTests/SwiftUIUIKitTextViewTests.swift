import SwiftUI
import UIKit
import XCTest
 import OpenCodePocket

@MainActor
final class SwiftUIUIKitTextViewTests: XCTestCase {
    func test_textChangeReportsCursorPosition() {
        var isFocused = false
        var measuredHeight: CGFloat = 44
        var received: [(String, Int)] = []

        let view = SwiftUIUIKitTextView(
            isFocused: Binding(get: { isFocused }, set: { isFocused = $0 }),
            measuredHeight: Binding(get: { measuredHeight }, set: { measuredHeight = $0 }),
            text: "",
            cursorPosition: 0,
            minHeight: 44,
            maxHeight: 120,
            onTextAndCursorChange: { text, cursor in received.append((text, cursor)) },
            onFocusChange: { _ in }
        )

        let coordinator = view.makeCoordinator()
        let textView = UITextView()
        textView.text = "hello"
        textView.selectedRange = NSRange(location: 3, length: 0)

        coordinator.textViewDidChange(textView)

        XCTAssertEqual(received.last?.0, "hello")
        XCTAssertEqual(received.last?.1, 3)
    }

    func test_selectionChangeReportsCursorPosition() {
        var isFocused = false
        var measuredHeight: CGFloat = 44
        var received: [(String, Int)] = []

        let view = SwiftUIUIKitTextView(
            isFocused: Binding(get: { isFocused }, set: { isFocused = $0 }),
            measuredHeight: Binding(get: { measuredHeight }, set: { measuredHeight = $0 }),
            text: "abc",
            cursorPosition: 0,
            minHeight: 44,
            maxHeight: 120,
            onTextAndCursorChange: { text, cursor in received.append((text, cursor)) },
            onFocusChange: { _ in }
        )

        let coordinator = view.makeCoordinator()
        let textView = UITextView()
        textView.text = "abc"
        textView.selectedRange = NSRange(location: 1, length: 0)

        coordinator.textViewDidChangeSelection(textView)

        XCTAssertEqual(received.last?.0, "abc")
        XCTAssertEqual(received.last?.1, 1)
    }

    func test_applyStateDoesNotOverrideTextSelectionWhenCursorMatches() {
        var isFocused = false
        var measuredHeight: CGFloat = 44

        let view = SwiftUIUIKitTextView(
            isFocused: Binding(get: { isFocused }, set: { isFocused = $0 }),
            measuredHeight: Binding(get: { measuredHeight }, set: { measuredHeight = $0 }),
            text: "abcdef",
            cursorPosition: 1,
            minHeight: 44,
            maxHeight: 120,
            onTextAndCursorChange: { _, _ in },
            onFocusChange: { _ in }
        )

        let coordinator = view.makeCoordinator()
        let textView = UITextView()
        textView.text = "abcdef"
        textView.selectedRange = NSRange(location: 1, length: 3)

        view.applyState(to: textView, coordinator: coordinator)

        XCTAssertEqual(textView.selectedRange.location, 1)
        XCTAssertEqual(textView.selectedRange.length, 3)
    }

    func test_applyStateCollapsesSelectionWhenCursorMoves() {
        var isFocused = false
        var measuredHeight: CGFloat = 44

        let view = SwiftUIUIKitTextView(
            isFocused: Binding(get: { isFocused }, set: { isFocused = $0 }),
            measuredHeight: Binding(get: { measuredHeight }, set: { measuredHeight = $0 }),
            text: "abcdef",
            cursorPosition: 4,
            minHeight: 44,
            maxHeight: 120,
            onTextAndCursorChange: { _, _ in },
            onFocusChange: { _ in }
        )

        let coordinator = view.makeCoordinator()
        let textView = UITextView()
        textView.text = "abcdef"
        textView.selectedRange = NSRange(location: 1, length: 3)

        view.applyState(to: textView, coordinator: coordinator)

        XCTAssertEqual(textView.selectedRange.location, 4)
        XCTAssertEqual(textView.selectedRange.length, 0)
    }

    func test_updateHeightClampsToMaxHeight() async {
        var isFocused = false
        var measuredHeight: CGFloat = 44

        let view = SwiftUIUIKitTextView(
            isFocused: Binding(get: { isFocused }, set: { isFocused = $0 }),
            measuredHeight: Binding(get: { measuredHeight }, set: { measuredHeight = $0 }),
            text: "",
            cursorPosition: 0,
            minHeight: 44,
            maxHeight: 120,
            onTextAndCursorChange: { _, _ in },
            onFocusChange: { _ in }
        )

        let coordinator = view.makeCoordinator()
        let textView = UITextView()
        textView.textContainer.lineFragmentPadding = 0
        textView.textContainerInset = UIEdgeInsets(top: 10, left: 14, bottom: 10, right: 14)
        textView.bounds = CGRect(x: 0, y: 0, width: 240, height: 44)

        textView.text = Array(repeating: "line", count: 80).joined(separator: "\n")

        coordinator.updateHeight(textView)

        await waitUntil({ abs(measuredHeight - 120) <= 0.5 })
        XCTAssertTrue(textView.isScrollEnabled)
    }
}
