import SwiftUI
import UIKit

struct ChatComposerInputTextView: UIViewRepresentable {
    let text: String
    let cursorPosition: Int
    @Binding var isFocused: Bool
    let isEditable: Bool
    @Binding var dynamicHeight: CGFloat
    let onTextAndCursorChange: (String, Int) -> Void
    let onPasteImageData: ([Data]) -> Void

    private let minVisibleLines: CGFloat = 1
    private let maxVisibleLines: CGFloat = 6

    func makeUIView(context: Context) -> ChatComposerPasteInterceptingTextView {
        let textView = ChatComposerPasteInterceptingTextView(frame: .zero, textContainer: nil)
        textView.delegate = context.coordinator
        textView.backgroundColor = .clear
        textView.font = UIFont.preferredFont(forTextStyle: .body)
        textView.textColor = .label
        textView.textContainerInset = .zero
        textView.textContainer.lineFragmentPadding = 0
        textView.autocorrectionType = .default
        textView.autocapitalizationType = .sentences
        textView.isScrollEnabled = true
        textView.showsVerticalScrollIndicator = false
        textView.keyboardDismissMode = .none
        textView.onPasteImageData = onPasteImageData
        textView.accessibilityIdentifier = "chat.composer.input"
        context.coordinator.applyExternalState(
            text: text,
            cursorPosition: cursorPosition,
            to: textView
        )
        context.coordinator.syncFocusIfNeeded(
            for: textView,
            shouldBeFocused: isFocused,
            isEditable: isEditable
        )
        context.coordinator.updateHeight(for: textView)
        return textView
    }

    func updateUIView(_ uiView: ChatComposerPasteInterceptingTextView, context: Context) {
        context.coordinator.updateBindings(
            isFocused: $isFocused,
            dynamicHeight: $dynamicHeight,
            onTextAndCursorChange: onTextAndCursorChange
        )
        uiView.isEditable = isEditable
        uiView.isSelectable = true
        uiView.font = UIFont.preferredFont(forTextStyle: .body)
        uiView.onPasteImageData = onPasteImageData
        context.coordinator.applyExternalState(
            text: text,
            cursorPosition: cursorPosition,
            to: uiView
        )
        context.coordinator.syncFocusIfNeeded(
            for: uiView,
            shouldBeFocused: isFocused,
            isEditable: isEditable
        )
        context.coordinator.updateHeight(for: uiView)
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(
            isFocused: $isFocused,
            dynamicHeight: $dynamicHeight,
            onTextAndCursorChange: onTextAndCursorChange,
            minVisibleLines: minVisibleLines,
            maxVisibleLines: maxVisibleLines
        )
    }

    final class Coordinator: NSObject, UITextViewDelegate {
        private var isFocused: Binding<Bool>
        private var dynamicHeight: Binding<CGFloat>
        private var onTextAndCursorChange: (String, Int) -> Void
        private let minVisibleLines: CGFloat
        private let maxVisibleLines: CGFloat
        private var lastFocusBindingValue: Bool
        private var pendingHeightValue: CGFloat?
        private var isHeightCommitScheduled = false
        private var isApplyingExternalState = false

        init(
            isFocused: Binding<Bool>,
            dynamicHeight: Binding<CGFloat>,
            onTextAndCursorChange: @escaping (String, Int) -> Void,
            minVisibleLines: CGFloat,
            maxVisibleLines: CGFloat
        ) {
            self.isFocused = isFocused
            self.dynamicHeight = dynamicHeight
            self.onTextAndCursorChange = onTextAndCursorChange
            self.minVisibleLines = minVisibleLines
            self.maxVisibleLines = maxVisibleLines
            self.lastFocusBindingValue = isFocused.wrappedValue
        }

        func updateBindings(
            isFocused: Binding<Bool>,
            dynamicHeight: Binding<CGFloat>,
            onTextAndCursorChange: @escaping (String, Int) -> Void
        ) {
            self.isFocused = isFocused
            self.dynamicHeight = dynamicHeight
            self.onTextAndCursorChange = onTextAndCursorChange
        }

        func applyExternalState(text: String, cursorPosition: Int, to textView: UITextView) {
            isApplyingExternalState = true
            defer { isApplyingExternalState = false }

            if textView.text != text {
                textView.text = text
            }

            let clampedCursor = max(0, min(cursorPosition, text.utf16.count))
            if textView.selectedRange.location != clampedCursor || textView.selectedRange.length != 0 {
                textView.selectedRange = NSRange(location: clampedCursor, length: 0)
            }
        }

        func textViewDidChange(_ textView: UITextView) {
            guard !isApplyingExternalState else { return }
            onTextAndCursorChange(textView.text ?? "", textView.selectedRange.location)
            updateHeight(for: textView)
        }

        func textViewDidChangeSelection(_ textView: UITextView) {
            guard !isApplyingExternalState else { return }
            onTextAndCursorChange(textView.text ?? "", textView.selectedRange.location)
        }

        func textViewDidBeginEditing(_ textView: UITextView) {
            if !isFocused.wrappedValue {
                isFocused.wrappedValue = true
            }
        }

        func textViewDidEndEditing(_ textView: UITextView) {
            if isFocused.wrappedValue {
                isFocused.wrappedValue = false
            }
        }

        fileprivate func updateHeight(for textView: UITextView) {
            let lineHeight = (textView.font ?? UIFont.preferredFont(forTextStyle: .body)).lineHeight
            let minHeight = lineHeight * minVisibleLines
            let maxHeight = lineHeight * maxVisibleLines
            let targetWidth = max(textView.bounds.width, 1)
            let fitSize = CGSize(width: targetWidth, height: .greatestFiniteMagnitude)
            var measured = textView.sizeThatFits(fitSize).height
            let shouldScroll = measured > maxHeight + 0.5
            if textView.isScrollEnabled != shouldScroll {
                textView.isScrollEnabled = shouldScroll
                textView.invalidateIntrinsicContentSize()
                measured = textView.sizeThatFits(fitSize).height
            }
            let clamped = min(max(measured, minHeight), maxHeight)

            if abs(dynamicHeight.wrappedValue - clamped) > 0.5 {
                scheduleHeightCommit(clamped)
            }
        }

        private func scheduleHeightCommit(_ height: CGFloat) {
            pendingHeightValue = height
            guard !isHeightCommitScheduled else { return }

            isHeightCommitScheduled = true
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                self.isHeightCommitScheduled = false

                guard let pendingHeight = self.pendingHeightValue else { return }
                self.pendingHeightValue = nil

                if abs(self.dynamicHeight.wrappedValue - pendingHeight) > 0.5 {
                    self.dynamicHeight.wrappedValue = pendingHeight
                }
            }
        }

        fileprivate func syncFocusIfNeeded(
            for textView: UITextView,
            shouldBeFocused: Bool,
            isEditable: Bool
        ) {
            let focusBindingDidChange = shouldBeFocused != lastFocusBindingValue
            lastFocusBindingValue = shouldBeFocused

            guard focusBindingDidChange else { return }

            if shouldBeFocused && isEditable {
                guard !textView.isFirstResponder else { return }
                DispatchQueue.main.async {
                    textView.becomeFirstResponder()
                }
            } else if !shouldBeFocused || !isEditable {
                guard textView.isFirstResponder else { return }
                DispatchQueue.main.async {
                    textView.resignFirstResponder()
                }
            }
        }
    }
}

final class ChatComposerPasteInterceptingTextView: UITextView {
    var onPasteImageData: (([Data]) -> Void)?

    override var intrinsicContentSize: CGSize {
        CGSize(width: UIView.noIntrinsicMetric, height: super.intrinsicContentSize.height)
    }

    override func canPerformAction(_ action: Selector, withSender sender: Any?) -> Bool {
        if action == #selector(UIResponderStandardEditActions.paste(_:)) {
            let pasteboard = UIPasteboard.general
            if pasteboard.hasImages { return true }
        }
        return super.canPerformAction(action, withSender: sender)
    }

    override func paste(_ sender: Any?) {
        let pasteboard = UIPasteboard.general
        let imageDataItems = imageDataFromPasteboard(pasteboard)
        if !imageDataItems.isEmpty {
            onPasteImageData?(imageDataItems)
            if pasteboard.hasStrings {
                super.paste(sender)
            }
            return
        }
        super.paste(sender)
    }

    private static let maxIntakeDimension: CGFloat = 1600
    private static let intakeCompressionQuality: CGFloat = 0.8

    private func imageDataFromPasteboard(_ pasteboard: UIPasteboard) -> [Data] {
        var imageDataItems: [Data] = []

        if let images = pasteboard.images, !images.isEmpty {
            imageDataItems = images.compactMap { Self.downscaledJPEGData(from: $0) }
        } else if let image = pasteboard.image {
            if let data = Self.downscaledJPEGData(from: image) {
                imageDataItems = [data]
            }
        }

        return imageDataItems
    }

    private static func downscaledJPEGData(from image: UIImage) -> Data? {
        let size = image.size
        guard size.width > 0, size.height > 0 else { return nil }

        let longestSide = max(size.width, size.height)
        let scale = min(1, maxIntakeDimension / longestSide)

        if scale < 1 {
            let target = CGSize(width: floor(size.width * scale), height: floor(size.height * scale))
            let format = UIGraphicsImageRendererFormat.default()
            format.scale = 1
            let resized = UIGraphicsImageRenderer(size: target, format: format).image { _ in
                image.draw(in: CGRect(origin: .zero, size: target))
            }
            return resized.jpegData(compressionQuality: intakeCompressionQuality)
        }

        return image.jpegData(compressionQuality: intakeCompressionQuality)
    }
}
