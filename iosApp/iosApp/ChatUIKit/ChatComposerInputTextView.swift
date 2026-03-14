import SwiftUI
import UIKit

struct ChatComposerPastedImagePayload {
    let filename: String
    let mimeType: String
    let data: Data
}

struct ChatComposerInputTextView: UIViewRepresentable {
    let text: String
    let cursorPosition: Int
    @Binding var isFocused: Bool
    let isEditable: Bool
    @Binding var dynamicHeight: CGFloat
    let onTextAndCursorChange: (String, Int) -> Void
    let onPasteImageData: ([ChatComposerPastedImagePayload]) -> Void

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
    var onPasteImageData: (([ChatComposerPastedImagePayload]) -> Void)?

    private struct PasteboardImageRepresentation {
        let pasteboardType: String
        let mimeType: String
        let fileExtension: String
    }

    private static let preferredRepresentations: [PasteboardImageRepresentation] = [
        .init(pasteboardType: "public.png", mimeType: "image/png", fileExtension: "png"),
        .init(pasteboardType: "public.webp", mimeType: "image/webp", fileExtension: "webp"),
        .init(pasteboardType: "com.compuserve.gif", mimeType: "image/gif", fileExtension: "gif"),
        .init(pasteboardType: "public.heic", mimeType: "image/heic", fileExtension: "heic"),
        .init(pasteboardType: "public.heif", mimeType: "image/heif", fileExtension: "heif"),
        .init(pasteboardType: "public.jpeg", mimeType: "image/jpeg", fileExtension: "jpg"),
    ]

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
        let imageItems = imagePayloadsFromPasteboard(pasteboard)
        if !imageItems.isEmpty {
            onPasteImageData?(imageItems)
            if pasteboard.hasStrings {
                super.paste(sender)
            }
            return
        }
        super.paste(sender)
    }

    private func imagePayloadsFromPasteboard(_ pasteboard: UIPasteboard) -> [ChatComposerPastedImagePayload] {
        let directItems = pasteboard.items.enumerated().compactMap { index, item in
            Self.directImagePayload(from: item, index: index)
        }
        if !directItems.isEmpty {
            return directItems
        }

        if let images = pasteboard.images, !images.isEmpty {
            return images.enumerated().compactMap { index, image in
                Self.fallbackPayload(from: image, index: index)
            }
        }

        if let image = pasteboard.image, let payload = Self.fallbackPayload(from: image, index: 0) {
            return [payload]
        }

        return []
    }

    private static func directImagePayload(
        from item: [String: Any],
        index: Int
    ) -> ChatComposerPastedImagePayload? {
        for representation in preferredRepresentations {
            guard let rawValue = item[representation.pasteboardType] else { continue }
            guard let data = data(from: rawValue), !data.isEmpty else { continue }

            let token = String(UUID().uuidString.prefix(8))
            return ChatComposerPastedImagePayload(
                filename: "paste_\(token)_\(index).\(representation.fileExtension)",
                mimeType: representation.mimeType,
                data: data
            )
        }

        if let image = item.values.compactMap({ $0 as? UIImage }).first {
            return fallbackPayload(from: image, index: index)
        }

        return nil
    }

    private static func fallbackPayload(from image: UIImage, index: Int) -> ChatComposerPastedImagePayload? {
        let token = String(UUID().uuidString.prefix(8))

        if image.hasAlphaChannel, let pngData = image.pngData() {
            return ChatComposerPastedImagePayload(
                filename: "paste_\(token)_\(index).png",
                mimeType: "image/png",
                data: pngData
            )
        }

        guard let jpegData = image.jpegData(compressionQuality: 0.9) else { return nil }
        return ChatComposerPastedImagePayload(
            filename: "paste_\(token)_\(index).jpg",
            mimeType: "image/jpeg",
            data: jpegData
        )
    }

    private static func data(from rawValue: Any) -> Data? {
        if let data = rawValue as? Data {
            return data
        }
        if let nsData = rawValue as? NSData {
            return Data(referencing: nsData)
        }
        return nil
    }
}

private extension UIImage {
    var hasAlphaChannel: Bool {
        guard let alphaInfo = cgImage?.alphaInfo else { return true }
        switch alphaInfo {
        case .first, .last, .premultipliedFirst, .premultipliedLast:
            return true
        default:
            return false
        }
    }
}
