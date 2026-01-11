import SwiftUI
import UIKit

struct SwiftUIUIKitTextView: UIViewRepresentable {
    @Binding var isFocused: Bool
    @Binding var measuredHeight: CGFloat

    let text: String
    let cursorPosition: Int
    let minHeight: CGFloat
    let maxHeight: CGFloat

    let onTextAndCursorChange: (_ text: String, _ cursorPosition: Int) -> Void
    let onFocusChange: (_ isFocused: Bool) -> Void

    final class Coordinator: NSObject, UITextViewDelegate {
        var parent: SwiftUIUIKitTextView
        var isUpdatingFromSwiftUI: Bool = false

        init(parent: SwiftUIUIKitTextView) {
            self.parent = parent
        }

        func textViewDidBeginEditing(_ textView: UITextView) {
            parent.isFocused = true
            parent.onFocusChange(true)
        }

        func textViewDidEndEditing(_ textView: UITextView) {
            parent.isFocused = false
            parent.onFocusChange(false)
        }

        func textViewDidChange(_ textView: UITextView) {
            guard !isUpdatingFromSwiftUI else { return }
            parent.onTextAndCursorChange(textView.text ?? "", textView.selectedRange.location)
            updateHeight(textView)
        }

        func textViewDidChangeSelection(_ textView: UITextView) {
            guard !isUpdatingFromSwiftUI else { return }
            parent.onTextAndCursorChange(textView.text ?? "", textView.selectedRange.location)
        }

        func updateHeight(_ textView: UITextView) {
            let targetWidth = max(1, textView.bounds.width)
            let fittingSize = CGSize(width: targetWidth, height: .greatestFiniteMagnitude)
            let size = textView.sizeThatFits(fittingSize)
            let clampedHeight = min(parent.maxHeight, max(parent.minHeight, size.height))

            DispatchQueue.main.async {
                if abs(self.parent.measuredHeight - clampedHeight) > 0.5 {
                    self.parent.measuredHeight = clampedHeight
                }
                textView.isScrollEnabled = size.height > self.parent.maxHeight
            }
        }
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(parent: self)
    }

    func makeUIView(context: Context) -> UITextView {
        let textView = UITextView()
        textView.delegate = context.coordinator

        textView.isEditable = true
        textView.isSelectable = true
        textView.isScrollEnabled = false
        textView.showsVerticalScrollIndicator = false
        textView.showsHorizontalScrollIndicator = false
        textView.backgroundColor = .clear

        textView.font = UIFont.preferredFont(forTextStyle: .body)
        textView.textColor = UIColor.label
        textView.textContainer.lineFragmentPadding = 0
        textView.textContainerInset = UIEdgeInsets(top: 10, left: 14, bottom: 10, right: 14)

        textView.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        textView.setContentCompressionResistancePriority(.defaultLow, for: .vertical)

        textView.text = text
        textView.selectedRange = NSRange(location: max(0, min(cursorPosition, text.utf16.count)), length: 0)

        DispatchQueue.main.async {
            context.coordinator.updateHeight(textView)
        }

        return textView
    }

    func updateUIView(_ uiView: UITextView, context: Context) {
        applyState(to: uiView, coordinator: context.coordinator)
    }

    func applyState(to uiView: UITextView, coordinator: Coordinator) {
        coordinator.parent = self
        coordinator.isUpdatingFromSwiftUI = true
        defer { coordinator.isUpdatingFromSwiftUI = false }

        if uiView.text != text {
            uiView.text = text
        }

        // Cursor indices from Kotlin and UITextView are UTF-16 code unit offsets (not grapheme clusters).
        let clampedCursor = max(0, min(cursorPosition, text.utf16.count))
        if uiView.selectedRange.location != clampedCursor {
            uiView.selectedRange = NSRange(location: clampedCursor, length: 0)
        }

        if isFocused && !uiView.isFirstResponder {
            DispatchQueue.main.async { uiView.becomeFirstResponder() }
        } else if !isFocused && uiView.isFirstResponder {
            DispatchQueue.main.async { uiView.resignFirstResponder() }
        }

        DispatchQueue.main.async {
            coordinator.updateHeight(uiView)
        }
    }
}
