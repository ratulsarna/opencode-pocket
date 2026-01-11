import UIKit
import XCTest
 import OpenCodePocket

final class OCMobileMarkdownRendererTests: XCTestCase {
    func test_softBreakPreservedAsNewline() {
        let blocks = OCMobileMarkdownRenderer.shared.renderBlocks(
            markdown: "hello\nworld",
            isSecondary: false,
            traitCollection: UITraitCollection()
        )

        XCTAssertEqual(blocks.count, 1)
        XCTAssertEqual(blocks.first?.kind, .paragraph)
        XCTAssertEqual(blocks.first?.attributedText?.string, "hello\nworld")
    }

    func test_headingListAndCodeBlockProduceBlocksInOrder() {
        let markdown = """
        # Heading

        - one
        - two

        ```swift
        print("hi")
        ```
        """

        let blocks = OCMobileMarkdownRenderer.shared.renderBlocks(
            markdown: markdown,
            isSecondary: false,
            traitCollection: UITraitCollection()
        )

        let kinds = blocks.map(\.kind)
        XCTAssertEqual(kinds, [.heading(level: 1), .list, .codeBlock(language: "swift")])
    }

    func test_codeBlockUsesMonospacedFont() {
        let markdown = """
        ```swift
        print("hi")
        ```
        """

        let blocks = OCMobileMarkdownRenderer.shared.renderBlocks(
            markdown: markdown,
            isSecondary: false,
            traitCollection: UITraitCollection()
        )

        guard blocks.count == 1 else {
            XCTFail("Expected 1 block, got \(blocks.count)")
            return
        }
        guard case .codeBlock = blocks[0].kind else {
            XCTFail("Expected code block kind, got \(blocks[0].kind)")
            return
        }

        guard let attributed = blocks[0].attributedText else {
            XCTFail("Expected attributedText for code block")
            return
        }
        let fullRange = NSRange(location: 0, length: attributed.length)
        var sawMonospace = false

        attributed.enumerateAttribute(.font, in: fullRange) { value, _, _ in
            guard let font = value as? UIFont else { return }
            if font.fontDescriptor.symbolicTraits.contains(.traitMonoSpace) {
                sawMonospace = true
            }
        }

        XCTAssertTrue(sawMonospace)
    }

    func test_codeBlockUsesCharWrapping() {
        let markdown = """
        ```
        abcdefghijklmnopqrstuvwxyz
        ```
        """

        let blocks = OCMobileMarkdownRenderer.shared.renderBlocks(
            markdown: markdown,
            isSecondary: false,
            traitCollection: UITraitCollection()
        )

        guard blocks.count == 1 else {
            XCTFail("Expected 1 block, got \(blocks.count)")
            return
        }
        guard case .codeBlock = blocks[0].kind else {
            XCTFail("Expected code block kind, got \(blocks[0].kind)")
            return
        }
        guard let attributed = blocks[0].attributedText else {
            XCTFail("Expected attributedText for code block")
            return
        }
        guard let paragraphStyle = attributed.attribute(.paragraphStyle, at: 0, effectiveRange: nil) as? NSParagraphStyle else {
            XCTFail("Expected paragraphStyle for code block")
            return
        }

        XCTAssertEqual(paragraphStyle.lineBreakMode, .byCharWrapping)
    }

    func test_baseTextStyleSubheadlineUsesSubheadlineFont() {
        let markdown = "Hello world"
        let traits = UITraitCollection()

        let blocks = OCMobileMarkdownRenderer.shared.renderBlocks(
            markdown: markdown,
            isSecondary: false,
            traitCollection: traits,
            baseTextStyle: .subheadline
        )

        guard let attributed = blocks.first?.attributedText else {
            XCTFail("Expected attributedText for paragraph")
            return
        }

        guard let font = attributed.attribute(.font, at: 0, effectiveRange: nil) as? UIFont else {
            XCTFail("Expected font attribute")
            return
        }

        let expected = UIFont.preferredFont(forTextStyle: .subheadline, compatibleWith: traits)
        XCTAssertEqual(Double(font.pointSize), Double(expected.pointSize), accuracy: 0.01)
    }

    func test_samAppFileLinkSetsLinkAttribute() {
        let markdown = "[file](oc-pocket-file:%2FUsers%2Fme%2Fnotes%2Ftodo.md)"

        let blocks = OCMobileMarkdownRenderer.shared.renderBlocks(
            markdown: markdown,
            isSecondary: false,
            traitCollection: UITraitCollection()
        )

        guard let first = blocks.first else {
            XCTFail("Expected at least 1 block")
            return
        }

        guard let attributed = first.attributedText else {
            XCTFail("Expected attributedText for paragraph")
            return
        }
        let fullRange = NSRange(location: 0, length: attributed.length)
        var urls: [URL] = []

        attributed.enumerateAttribute(.link, in: fullRange) { value, _, _ in
            if let url = value as? URL {
                urls.append(url)
            }
        }

        XCTAssertEqual(urls.count, 1)
        XCTAssertEqual(urls.first?.scheme?.lowercased(), "oc-pocket-file")
        XCTAssertEqual(urls.first?.absoluteString, "oc-pocket-file:%2FUsers%2Fme%2Fnotes%2Ftodo.md")
    }

    func test_codeBlockInsideBlockQuotePreservesKind() {
        let markdown = """
        > Quote
        >
        > ```swift
        > print("hi")
        > ```
        """

        let blocks = OCMobileMarkdownRenderer.shared.renderBlocks(
            markdown: markdown,
            isSecondary: false,
            traitCollection: UITraitCollection()
        )

        XCTAssertEqual(blocks.count, 1)
        XCTAssertEqual(blocks.first?.kind, .blockQuote)
        XCTAssertEqual(blocks.first?.children.map(\.kind), [.paragraph, .codeBlock(language: "swift")])
    }

    func test_orderedListIndentExpandsForLargeMarkers() {
        let markdown = """
        999. one
        1000. two
        """

        let traits = UITraitCollection()
        let blocks = OCMobileMarkdownRenderer.shared.renderBlocks(
            markdown: markdown,
            isSecondary: false,
            traitCollection: traits
        )

        XCTAssertEqual(blocks.count, 1)
        XCTAssertEqual(blocks.first?.kind, .list)

        guard let attributed = blocks.first?.attributedText else {
            XCTFail("Expected attributedText for list")
            return
        }
        guard let paragraphStyle = attributed.attribute(.paragraphStyle, at: 0, effectiveRange: nil) as? NSParagraphStyle else {
            XCTFail("Expected paragraphStyle for list")
            return
        }

        let font = UIFont.preferredFont(forTextStyle: .body, compatibleWith: traits)
        let markerWidth = ("1000." as NSString).size(withAttributes: [.font: font]).width
        XCTAssertGreaterThanOrEqual(paragraphStyle.headIndent, ceil(markerWidth) + 8 - 0.5)
    }
}
