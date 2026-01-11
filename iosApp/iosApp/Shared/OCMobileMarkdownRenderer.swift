import Foundation
import UIKit
import Markdown
import CryptoKit

enum OCMobileMarkdownBlockKind: Equatable {
    case paragraph
    case heading(level: Int)
    case list
    case codeBlock(language: String?)
    case blockQuote
}

struct OCMobileMarkdownBlock: Equatable {
    let kind: OCMobileMarkdownBlockKind
    let attributedText: NSAttributedString?
    let children: [OCMobileMarkdownBlock]

    init(kind: OCMobileMarkdownBlockKind, attributedText: NSAttributedString) {
        self.kind = kind
        self.attributedText = attributedText
        self.children = []
    }

    init(kind: OCMobileMarkdownBlockKind, children: [OCMobileMarkdownBlock]) {
        self.kind = kind
        self.attributedText = nil
        self.children = children
    }

    static func == (lhs: OCMobileMarkdownBlock, rhs: OCMobileMarkdownBlock) -> Bool {
        guard lhs.kind == rhs.kind else { return false }
        if lhs.children != rhs.children { return false }

        switch (lhs.attributedText, rhs.attributedText) {
        case (nil, nil):
            return true
        case let (l?, r?):
            return l.isEqual(r)
        default:
            return false
        }
    }
}

final class OCMobileMarkdownRenderer {
    static let shared = OCMobileMarkdownRenderer()

    private final class CachedOutput: NSObject {
        let blocks: [OCMobileMarkdownBlock]
        init(blocks: [OCMobileMarkdownBlock]) {
            self.blocks = blocks
        }
    }

    private let cache = NSCache<NSString, CachedOutput>()
    private let maxCachedMarkdownBytes = 64 * 1024

    private init() {
        // Keep this conservative: streaming responses can generate many incremental markdown strings.
        // Also, NSCache keys are strongly held, so avoid retaining large markdown in keys (see `cacheKeyFor`).
        cache.countLimit = 80
        cache.totalCostLimit = 24 * 1024 * 1024

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(onMemoryWarning),
            name: UIApplication.didReceiveMemoryWarningNotification,
            object: nil
        )
    }

    func renderBlocks(
        markdown: String,
        isSecondary: Bool,
        traitCollection: UITraitCollection,
        baseTextStyle: UIFont.TextStyle = .body
    ) -> [OCMobileMarkdownBlock] {
        let shouldCache = markdown.utf8.count <= maxCachedMarkdownBytes

        var cacheKey: NSString?
        if shouldCache {
            cacheKey = cacheKeyFor(
                markdown: markdown,
                isSecondary: isSecondary,
                traitCollection: traitCollection,
                baseTextStyle: baseTextStyle
            )
            if let cached = cache.object(forKey: cacheKey!) {
                return cached.blocks
            }
        }

        let document = Document(parsing: markdown)
        let style = OCMobileMarkdownStyle(isSecondary: isSecondary, traitCollection: traitCollection, baseTextStyle: baseTextStyle)

        var blocks: [OCMobileMarkdownBlock] = []
        blocks.reserveCapacity(document.childCount)

        for child in document.children {
            blocks.append(contentsOf: renderBlock(child, style: style, indentLevel: 0))
        }

        if shouldCache {
            let output = CachedOutput(blocks: blocks)
            if let cacheKey {
                cache.setObject(output, forKey: cacheKey, cost: estimateCost(markdown: markdown, blocks: blocks))
            }
        }
        return blocks
    }

    func cachedBlocks(
        markdown: String,
        isSecondary: Bool,
        traitCollection: UITraitCollection,
        baseTextStyle: UIFont.TextStyle = .body
    ) -> [OCMobileMarkdownBlock]? {
        guard markdown.utf8.count <= maxCachedMarkdownBytes else { return nil }
        let cacheKey = cacheKeyFor(
            markdown: markdown,
            isSecondary: isSecondary,
            traitCollection: traitCollection,
            baseTextStyle: baseTextStyle
        )
        return cache.object(forKey: cacheKey)?.blocks
    }

    private func cacheKeyFor(
        markdown: String,
        isSecondary: Bool,
        traitCollection: UITraitCollection,
        baseTextStyle: UIFont.TextStyle
    ) -> NSString {
        let category = traitCollection.preferredContentSizeCategory.rawValue
        let uiStyle = traitCollection.userInterfaceStyle.rawValue
        let textStyle = baseTextStyle.rawValue

        // Never embed raw markdown in the key: it can be very large, and NSCache retains keys strongly.
        // Hash a stable input string instead.
        var hasherInput = Data()
        hasherInput.append(contentsOf: "\(isSecondary ? "S" : "P")|\(category)|\(uiStyle)|\(textStyle)|".utf8)
        hasherInput.append(contentsOf: markdown.utf8)

        let digest = SHA256.hash(data: hasherInput)
        let hex = digest.map { String(format: "%02x", $0) }.joined()
        return hex as NSString
    }

    @objc
    private func onMemoryWarning() {
        cache.removeAllObjects()
    }

    private func estimateCost(markdown: String, blocks: [OCMobileMarkdownBlock]) -> Int {
        // Approximate cost: markdown bytes + attributed characters. This doesn't account for attributes overhead,
        // but it's good enough to help NSCache evict large items under pressure.
        let markdownBytes = markdown.utf8.count
        let attributedChars = blocksAttributedCharacterCount(blocks)
        return markdownBytes + (attributedChars * 4)
    }

    private func blocksAttributedCharacterCount(_ blocks: [OCMobileMarkdownBlock]) -> Int {
        var count = 0
        for block in blocks {
            if let attributed = block.attributedText {
                count += attributed.length
            }
            if !block.children.isEmpty {
                count += blocksAttributedCharacterCount(block.children)
            }
        }
        return count
    }

    private func renderBlock(
        _ markup: Markup,
        style: OCMobileMarkdownStyle,
        indentLevel: Int
    ) -> [OCMobileMarkdownBlock] {
        if let paragraph = markup as? Paragraph {
            let attributed = renderInlineContainer(paragraph, baseFont: style.bodyFont, baseColor: style.textColor, style: style)
            return [OCMobileMarkdownBlock(kind: .paragraph, attributedText: applyLinkStyle(attributed, style: style))]
        }

        if let heading = markup as? Heading {
            let font = style.headingFont(level: heading.level)
            let attributed = renderInlineContainer(heading, baseFont: font, baseColor: style.textColor, style: style)
            return [OCMobileMarkdownBlock(kind: .heading(level: heading.level), attributedText: applyLinkStyle(attributed, style: style))]
        }

        if let code = markup as? CodeBlock {
            let attributed = NSMutableAttributedString(string: normalizeLineEndings(code.code))
            let fullRange = NSRange(location: 0, length: attributed.length)
            attributed.addAttribute(.font, value: style.codeBlockFont, range: fullRange)
            attributed.addAttribute(.foregroundColor, value: style.textColor, range: fullRange)
            let paragraphStyle = NSMutableParagraphStyle()
            paragraphStyle.lineBreakMode = .byCharWrapping
            attributed.addAttribute(.paragraphStyle, value: paragraphStyle, range: fullRange)
            return [OCMobileMarkdownBlock(kind: .codeBlock(language: code.language), attributedText: attributed)]
        }

        if let unordered = markup as? UnorderedList {
            return [OCMobileMarkdownBlock(kind: .list, attributedText: renderList(unordered.listItems, startIndex: nil, style: style, indentLevel: indentLevel))]
        }

        if let ordered = markup as? OrderedList {
            return [OCMobileMarkdownBlock(kind: .list, attributedText: renderList(ordered.listItems, startIndex: Int(ordered.startIndex), style: style, indentLevel: indentLevel))]
        }

        if let quote = markup as? BlockQuote {
            var inner: [OCMobileMarkdownBlock] = []
            for child in quote.children {
                inner.append(contentsOf: renderBlock(child, style: style, indentLevel: indentLevel + 1))
            }
            return [OCMobileMarkdownBlock(kind: .blockQuote, children: inner)]
        }

        // Fallback: render any inline containers as a paragraph, otherwise recurse into children.
        if let inlineContainer = markup as? InlineContainer {
            let attributed = renderInlineContainer(inlineContainer, baseFont: style.bodyFont, baseColor: style.textColor, style: style)
            return [OCMobileMarkdownBlock(kind: .paragraph, attributedText: applyLinkStyle(attributed, style: style))]
        }

        var output: [OCMobileMarkdownBlock] = []
        for child in markup.children {
            output.append(contentsOf: renderBlock(child, style: style, indentLevel: indentLevel))
        }
        return output
    }

    private func renderList(
        _ items: some Sequence<ListItem>,
        startIndex: Int?,
        style: OCMobileMarkdownStyle,
        indentLevel: Int
    ) -> NSAttributedString {
        let itemArray = Array(items)
        let output = NSMutableAttributedString()
        let baseIndent: CGFloat = 18 + (CGFloat(indentLevel) * 14)

        let markerSample: String
        if let startIndex {
            let last = max(startIndex, startIndex + itemArray.count - 1)
            markerSample = "\(last)."
        } else {
            markerSample = "•"
        }
        let markerWidth = (markerSample as NSString).size(withAttributes: [.font: style.bodyFont]).width
        let tabLocation: CGFloat = max(baseIndent, ceil(markerWidth) + 8)

        var index = startIndex ?? 1
        for (i, item) in itemArray.enumerated() {
            if i > 0 {
                output.append(NSAttributedString(string: "\n"))
            }

            let prefix: String
            if let _ = startIndex {
                prefix = "\(index)."
                index += 1
            } else {
                prefix = "•"
            }

            let line = NSMutableAttributedString(string: "\(prefix)\t")
            line.addAttribute(.font, value: style.bodyFont, range: NSRange(location: 0, length: line.length))
            line.addAttribute(.foregroundColor, value: style.textColor, range: NSRange(location: 0, length: line.length))

            // Render the item's first paragraph-like block into inline content.
            let itemContent = renderListItemInline(item, baseFont: style.bodyFont, baseColor: style.textColor, style: style)
            line.append(itemContent)

            let paragraphStyle = NSMutableParagraphStyle()
            paragraphStyle.firstLineHeadIndent = 0
            paragraphStyle.headIndent = tabLocation
            paragraphStyle.tabStops = [NSTextTab(textAlignment: .left, location: tabLocation)]
            paragraphStyle.defaultTabInterval = tabLocation
            paragraphStyle.lineBreakMode = .byWordWrapping

            line.addAttribute(.paragraphStyle, value: paragraphStyle, range: NSRange(location: 0, length: line.length))

            output.append(applyLinkStyle(line, style: style))
        }

        return output
    }

    private func renderListItemInline(
        _ item: ListItem,
        baseFont: UIFont,
        baseColor: UIColor,
        style: OCMobileMarkdownStyle
    ) -> NSAttributedString {
        // CommonMark list items typically contain Paragraph blocks.
        // We keep this conservative: render the first InlineContainer found, otherwise fall back to plain text of children.
        for child in item.children {
            if let inlineContainer = child as? InlineContainer {
                return renderInlineContainer(inlineContainer, baseFont: baseFont, baseColor: baseColor, style: style)
            }
        }

        let fallback = NSMutableAttributedString()
        for child in item.children {
            fallback.append(renderPlainText(from: child, font: baseFont, color: baseColor))
        }
        return fallback
    }

    private func renderInlineContainer(
        _ container: InlineContainer,
        baseFont: UIFont,
        baseColor: UIColor,
        style: OCMobileMarkdownStyle
    ) -> NSAttributedString {
        let output = NSMutableAttributedString()
        for child in container.children {
            output.append(renderInline(child, font: baseFont, color: baseColor, style: style))
        }

        if output.length == 0 {
            output.append(NSAttributedString(string: ""))
        }

        let paragraphStyle = NSMutableParagraphStyle()
        paragraphStyle.lineBreakMode = .byWordWrapping
        output.addAttribute(.paragraphStyle, value: paragraphStyle, range: NSRange(location: 0, length: output.length))

        return output
    }

    private func renderInline(
        _ markup: Markup,
        font: UIFont,
        color: UIColor,
        style: OCMobileMarkdownStyle
    ) -> NSAttributedString {
        if let text = markup as? Markdown.Text {
            return attributedString(text.string, font: font, color: color)
        }

        if markup is SoftBreak {
            return attributedString("\n", font: font, color: color)
        }

        if markup is LineBreak {
            return attributedString("\n", font: font, color: color)
        }

        if let inlineCode = markup as? InlineCode {
            let text = inlineCode.code
            let result = NSMutableAttributedString(string: text)
            let fullRange = NSRange(location: 0, length: result.length)
            result.addAttribute(.font, value: style.inlineCodeFont, range: fullRange)
            result.addAttribute(.foregroundColor, value: color, range: fullRange)
            result.addAttribute(.backgroundColor, value: style.inlineCodeBackgroundColor, range: fullRange)
            return result
        }

        if let strong = markup as? Strong {
            let nextFont = style.applyTraits(font: font, traits: .traitBold)
            return renderInlineChildren(strong, font: nextFont, color: color, style: style)
        }

        if let emphasis = markup as? Emphasis {
            let nextFont = style.applyTraits(font: font, traits: .traitItalic)
            return renderInlineChildren(emphasis, font: nextFont, color: color, style: style)
        }

        if let strikethrough = markup as? Strikethrough {
            let rendered = NSMutableAttributedString(attributedString: renderInlineChildren(strikethrough, font: font, color: color, style: style))
            rendered.addAttribute(.strikethroughStyle, value: NSUnderlineStyle.single.rawValue, range: NSRange(location: 0, length: rendered.length))
            return rendered
        }

        if let link = markup as? Link {
            let rendered = NSMutableAttributedString(attributedString: renderInlineChildren(link, font: font, color: color, style: style))
            let fullRange = NSRange(location: 0, length: rendered.length)
            if let url = style.url(from: link.destination) {
                rendered.addAttribute(.link, value: url, range: fullRange)
            }
            return rendered
        }

        // Fallback: recurse into children.
        return renderInlineChildren(markup, font: font, color: color, style: style)
    }

    private func renderInlineChildren(
        _ markup: Markup,
        font: UIFont,
        color: UIColor,
        style: OCMobileMarkdownStyle
    ) -> NSAttributedString {
        let output = NSMutableAttributedString()
        for child in markup.children {
            output.append(renderInline(child, font: font, color: color, style: style))
        }
        return output
    }

    private func renderPlainText(from markup: Markup, font: UIFont, color: UIColor) -> NSAttributedString {
        if let text = markup as? Markdown.Text {
            return attributedString(text.string, font: font, color: color)
        }
        let output = NSMutableAttributedString()
        for child in markup.children {
            output.append(renderPlainText(from: child, font: font, color: color))
        }
        return output
    }

    private func attributedString(_ string: String, font: UIFont, color: UIColor) -> NSAttributedString {
        let result = NSMutableAttributedString(string: string)
        let fullRange = NSRange(location: 0, length: result.length)
        result.addAttribute(.font, value: font, range: fullRange)
        result.addAttribute(.foregroundColor, value: color, range: fullRange)
        return result
    }

    private func applyLinkStyle(_ attributed: NSAttributedString, style: OCMobileMarkdownStyle) -> NSAttributedString {
        let mutable = NSMutableAttributedString(attributedString: attributed)
        let fullRange = NSRange(location: 0, length: mutable.length)
        mutable.enumerateAttribute(.link, in: fullRange) { value, range, _ in
            guard value != nil else { return }
            mutable.addAttribute(.foregroundColor, value: style.linkColor, range: range)
            mutable.addAttribute(.underlineStyle, value: NSUnderlineStyle.single.rawValue, range: range)
        }
        return mutable
    }

    private func normalizeLineEndings(_ text: String) -> String {
        text
            .replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
    }
}

private struct OCMobileMarkdownStyle {
    let isSecondary: Bool
    let traitCollection: UITraitCollection
    let baseTextStyle: UIFont.TextStyle

    var textColor: UIColor { isSecondary ? .secondaryLabel : .label }
    var linkColor: UIColor { .systemBlue }

    var bodyFont: UIFont { UIFont.preferredFont(forTextStyle: baseTextStyle, compatibleWith: traitCollection) }

    var inlineCodeFont: UIFont {
        let base = bodyFont
        return UIFont.monospacedSystemFont(ofSize: base.pointSize, weight: .regular)
    }

    var codeBlockFont: UIFont {
        let base = bodyFont
        // Slightly smaller to match typical chat/code aesthetics.
        return UIFont.monospacedSystemFont(ofSize: max(11, base.pointSize * 0.95), weight: .regular)
    }

    var inlineCodeBackgroundColor: UIColor {
        UIColor.secondarySystemBackground.withAlphaComponent(0.7)
    }

    init(isSecondary: Bool, traitCollection: UITraitCollection, baseTextStyle: UIFont.TextStyle) {
        self.isSecondary = isSecondary
        self.traitCollection = traitCollection
        self.baseTextStyle = baseTextStyle
    }

    func headingFont(level: Int) -> UIFont {
        switch level {
        case 1:
            return applyTraits(font: UIFont.preferredFont(forTextStyle: .title3, compatibleWith: traitCollection), traits: .traitBold)
        case 2:
            return applyTraits(font: UIFont.preferredFont(forTextStyle: .headline, compatibleWith: traitCollection), traits: .traitBold)
        default:
            return applyTraits(font: UIFont.preferredFont(forTextStyle: .subheadline, compatibleWith: traitCollection), traits: .traitBold)
        }
    }

    func applyTraits(font: UIFont, traits: UIFontDescriptor.SymbolicTraits) -> UIFont {
        let combined = font.fontDescriptor.symbolicTraits.union(traits)
        guard let descriptor = font.fontDescriptor.withSymbolicTraits(combined) else { return font }
        return UIFont(descriptor: descriptor, size: font.pointSize)
    }

    func url(from destination: String?) -> URL? {
        guard let destination, !destination.isEmpty else { return nil }
        if let url = URL(string: destination), url.scheme != nil { return url }
        let encoded = destination.addingPercentEncoding(withAllowedCharacters: .urlFragmentAllowed) ?? destination
        if let url = URL(string: encoded), url.scheme != nil { return url }
        return nil
    }
}
