import Foundation

enum MarkdownLinkParser {
    /// Parses Markdown links of the form `[label](url)` and returns an attributed string where the label has a `.link` attribute.
    /// Styling (colors/fonts/underline) is intentionally left to the caller.
    static func parse(_ text: String) -> NSMutableAttributedString {
        let output = NSMutableAttributedString()
        var index = text.startIndex

        while index < text.endIndex {
            guard let openBracket = text[index...].firstIndex(of: "[") else {
                output.append(NSAttributedString(string: String(text[index...])))
                break
            }

            if openBracket > index {
                output.append(NSAttributedString(string: String(text[index..<openBracket])))
            }

            guard let closeBracket = text[openBracket...].firstIndex(of: "]") else {
                output.append(NSAttributedString(string: String(text[openBracket...])))
                break
            }

            let afterCloseBracket = text.index(after: closeBracket)
            guard afterCloseBracket < text.endIndex, text[afterCloseBracket] == "(" else {
                output.append(NSAttributedString(string: "["))
                index = text.index(after: openBracket)
                continue
            }

            let urlStart = text.index(after: afterCloseBracket)
            guard let closeParen = text[urlStart...].firstIndex(of: ")") else {
                output.append(NSAttributedString(string: "["))
                index = text.index(after: openBracket)
                continue
            }

            let labelRange = text.index(after: openBracket)..<closeBracket
            let urlRange = urlStart..<closeParen
            let label = String(text[labelRange])
            let urlString = String(text[urlRange])

            let attributedLabel = NSMutableAttributedString(string: label)
            let encodedUrlString = urlString.addingPercentEncoding(withAllowedCharacters: .urlFragmentAllowed) ?? urlString
            if let url = URL(string: urlString) ?? URL(string: encodedUrlString), url.scheme != nil {
                attributedLabel.addAttribute(
                    NSAttributedString.Key.link,
                    value: url,
                    range: NSRange(location: 0, length: attributedLabel.length)
                )
            }
            output.append(attributedLabel)

            index = text.index(after: closeParen)
        }

        return output
    }
}
