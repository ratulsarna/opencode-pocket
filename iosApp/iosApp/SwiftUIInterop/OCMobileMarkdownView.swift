import SwiftUI
import UIKit
import ComposeApp

@MainActor
struct OCMobileMarkdownView: View {
    let text: String
    let isSecondary: Bool
    let onOpenFile: (String) -> Void
    let baseTextStyle: UIFont.TextStyle

    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.sizeCategory) private var sizeCategory

    @State private var blocks: [OCMobileMarkdownBlock] = []
    @State private var renderToken = UUID()

    init(
        text: String,
        isSecondary: Bool,
        onOpenFile: @escaping (String) -> Void,
        baseTextStyle: UIFont.TextStyle = .body
    ) {
        self.text = text
        self.isSecondary = isSecondary
        self.onOpenFile = onOpenFile
        self.baseTextStyle = baseTextStyle
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if blocks.isEmpty, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                Text(text)
                    .foregroundStyle(isSecondary ? .secondary : .primary)
                    .font(fallbackFont())
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                ForEach(Array(blocks.enumerated()), id: \.offset) { _, block in
                    OCMobileMarkdownBlockView(block: block, isSecondary: isSecondary, onOpenFile: onOpenFile)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .task(id: renderKey()) {
            render()
        }
    }

    private func renderKey() -> RenderKey {
        RenderKey(
            text: text,
            isSecondary: isSecondary,
            baseTextStyleRaw: baseTextStyle.rawValue,
            colorScheme: colorScheme,
            sizeCategory: sizeCategory
        )
    }

    private func render() {
        let token = UUID()
        renderToken = token

        let linkified = MarkdownInterop.shared.linkify(text: text)
        let traitCollection = makeTraitCollection()

        if let cached = OCMobileMarkdownRenderer.shared.cachedBlocks(
            markdown: linkified,
            isSecondary: isSecondary,
            traitCollection: traitCollection,
            baseTextStyle: baseTextStyle
        ) {
            blocks = cached
            return
        }

        blocks = []

        DispatchQueue.global(qos: .userInitiated).async {
            let rendered = OCMobileMarkdownRenderer.shared.renderBlocks(
                markdown: linkified,
                isSecondary: isSecondary,
                traitCollection: traitCollection,
                baseTextStyle: baseTextStyle
            )

            DispatchQueue.main.async {
                guard renderToken == token else { return }
                blocks = rendered
            }
        }
    }

    private func makeTraitCollection() -> UITraitCollection {
        let uiStyle: UIUserInterfaceStyle = (colorScheme == .dark) ? .dark : .light
        let styleTraits = UITraitCollection(userInterfaceStyle: uiStyle)

        // SwiftUI doesn't expose a direct `UIContentSizeCategory`, but UIApplication does.
        // This is good enough for dynamic type + cache key correctness.
        let categoryTraits = UITraitCollection(preferredContentSizeCategory: UIApplication.shared.preferredContentSizeCategory)

        return UITraitCollection(traitsFrom: [styleTraits, categoryTraits])
    }

    private func fallbackFont() -> Font {
        switch baseTextStyle {
        case .body:
            return .body
        case .subheadline:
            return .subheadline
        case .footnote:
            return .footnote
        case .caption1, .caption2:
            return .caption
        default:
            return .body
        }
    }
}

@MainActor
private struct OCMobileMarkdownBlockView: View {
    let block: OCMobileMarkdownBlock
    let isSecondary: Bool
    let onOpenFile: (String) -> Void

    var body: some View {
        switch block.kind {
        case .codeBlock:
            OCMobileMarkdownTextView(attributedText: block.attributedText, onOpenFile: onOpenFile)
                .padding(10)
                .background(Color(uiColor: UIColor.secondarySystemBackground.withAlphaComponent(0.9)))
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))

        case .blockQuote:
            HStack(alignment: .top, spacing: 10) {
                Rectangle()
                    .fill(Color(uiColor: UIColor.tertiaryLabel.withAlphaComponent(0.6)))
                    .frame(width: 3)
                    .clipShape(RoundedRectangle(cornerRadius: 1.5, style: .continuous))
                    .padding(.vertical, 2)

                VStack(alignment: .leading, spacing: 8) {
                    if block.children.isEmpty {
                        OCMobileMarkdownTextView(attributedText: block.attributedText, onOpenFile: onOpenFile)
                    } else {
                        ForEach(Array(block.children.enumerated()), id: \.offset) { _, child in
                            OCMobileMarkdownBlockView(block: child, isSecondary: isSecondary, onOpenFile: onOpenFile)
                        }
                    }
                }
            }

        default:
            OCMobileMarkdownTextView(attributedText: block.attributedText, onOpenFile: onOpenFile)
        }
    }
}

private struct RenderKey: Hashable {
    let text: String
    let isSecondary: Bool
    let baseTextStyleRaw: String
    let colorScheme: ColorScheme
    let sizeCategory: ContentSizeCategory
}

private struct OCMobileMarkdownTextView: UIViewRepresentable {
    let attributedText: NSAttributedString?
    let onOpenFile: (String) -> Void

    func makeUIView(context: Context) -> UITextView {
        let view = IntrinsicTextView()
        view.isEditable = false
        view.isSelectable = true
        view.isScrollEnabled = false
        view.backgroundColor = .clear
        view.dataDetectorTypes = []
        view.textContainerInset = .zero
        view.textContainer.lineFragmentPadding = 0
        view.textContainer.widthTracksTextView = true
        view.textContainer.heightTracksTextView = false
        view.textContainer.lineBreakMode = .byWordWrapping
        view.delegate = context.coordinator
        view.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        view.setContentCompressionResistancePriority(.defaultLow, for: .vertical)
        return view
    }

    func updateUIView(_ uiView: UITextView, context: Context) {
        let next = attributedText ?? NSAttributedString()
        if uiView.attributedText === next {
            return
        }
        uiView.attributedText = next
        uiView.invalidateIntrinsicContentSize()
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(onOpenFile: onOpenFile)
    }

    final class Coordinator: NSObject, UITextViewDelegate {
        let onOpenFile: (String) -> Void

        init(onOpenFile: @escaping (String) -> Void) {
            self.onOpenFile = onOpenFile
        }

        func textView(
            _ textView: UITextView,
            shouldInteractWith url: URL,
            in characterRange: NSRange,
            interaction: UITextItemInteraction
        ) -> Bool {
            // Reserve long-press for text selection. Only allow link interaction on a simple tap.
            if interaction != .invokeDefaultAction {
                return false
            }

            let absolute = url.absoluteString
            if absolute.hasPrefix("oc-pocket-file:") {
                let encoded = String(absolute.dropFirst("oc-pocket-file:".count))
                let decoded = encoded.removingPercentEncoding ?? encoded
                onOpenFile(decoded)
                return false
            }
            if url.scheme?.lowercased() == "oc-pocket" {
                return false
            }
            return true
        }
    }
}
