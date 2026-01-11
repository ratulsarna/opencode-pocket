import UIKit

enum UnifiedDiffLineKind {
    case fileHeader
    case hunkHeader
    case context
    case addition
    case deletion
    case meta
}

struct UnifiedDiffLine {
    let kind: UnifiedDiffLineKind
    let oldLine: Int?
    let newLine: Int?
    let text: String
}

enum UnifiedDiffParser {
    private static let hunkRegex = try! NSRegularExpression(pattern: "^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@")

    static func parse(_ diffText: String) -> [UnifiedDiffLine] {
        let rawLines = diffText.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
        var result: [UnifiedDiffLine] = []

        var oldLine: Int? = nil
        var newLine: Int? = nil

        func resetHunkState() {
            oldLine = nil
            newLine = nil
        }

        func isInHunk() -> Bool {
            oldLine != nil && newLine != nil
        }

        func isPlusFileHeader(_ raw: String) -> Bool {
            raw.hasPrefix("+++ ") || raw.hasPrefix("+++\t")
        }

        func isMinusFileHeader(_ raw: String) -> Bool {
            raw.hasPrefix("--- ") || raw.hasPrefix("---\t")
        }

        for raw in rawLines {
            if raw.hasPrefix("@@") {
                if let (oldStart, newStart) = parseHunkHeader(raw) {
                    oldLine = oldStart
                    newLine = newStart
                } else {
                    resetHunkState()
                }
                result.append(UnifiedDiffLine(kind: .hunkHeader, oldLine: nil, newLine: nil, text: raw))
                continue
            }

            // File-level headers reset line counters (important for multi-file diffs).
            if raw.hasPrefix("Index:") || raw.hasPrefix("===") || raw.hasPrefix("diff ") || raw.hasPrefix("index ") {
                resetHunkState()
                result.append(UnifiedDiffLine(kind: .fileHeader, oldLine: nil, newLine: nil, text: raw))
                continue
            }

            // Treat ---/+++ as headers only outside hunks; inside hunks these can be legitimate +/- content.
            if !isInHunk(), isMinusFileHeader(raw) || isPlusFileHeader(raw) {
                result.append(UnifiedDiffLine(kind: .fileHeader, oldLine: nil, newLine: nil, text: raw))
                continue
            }

            if raw.hasPrefix("\\ No newline at end of file") {
                result.append(UnifiedDiffLine(kind: .meta, oldLine: nil, newLine: nil, text: raw))
                continue
            }

            if raw.hasPrefix("+") {
                let line = UnifiedDiffLine(kind: .addition, oldLine: nil, newLine: newLine, text: String(raw.dropFirst()))
                result.append(line)
                if let n = newLine { newLine = n + 1 }
                continue
            }

            if raw.hasPrefix("-") {
                let line = UnifiedDiffLine(kind: .deletion, oldLine: oldLine, newLine: nil, text: String(raw.dropFirst()))
                result.append(line)
                if let o = oldLine { oldLine = o + 1 }
                continue
            }

            let contextText = raw.hasPrefix(" ") ? String(raw.dropFirst()) : raw
            let line = UnifiedDiffLine(kind: .context, oldLine: oldLine, newLine: newLine, text: contextText)
            result.append(line)
            if let o = oldLine { oldLine = o + 1 }
            if let n = newLine { newLine = n + 1 }
        }

        return result
    }

    private static func parseHunkHeader(_ line: String) -> (oldStart: Int, newStart: Int)? {
        let range = NSRange(line.startIndex..<line.endIndex, in: line)
        guard let match = hunkRegex.firstMatch(in: line, range: range) else { return nil }
        guard match.numberOfRanges >= 4 else { return nil }

        let ns = line as NSString
        let oldStart = Int(ns.substring(with: match.range(at: 1)))
        let newStart = Int(ns.substring(with: match.range(at: 3)))
        guard let oldStart, let newStart else { return nil }
        return (oldStart, newStart)
    }
}

struct UnifiedDiffLayout {
    let numberFont: UIFont
    let indicatorFont: UIFont
    let codeFont: UIFont
    let headerFont: UIFont

    let oldColumnWidth: CGFloat
    let newColumnWidth: CGFloat
    let indicatorWidth: CGFloat

    let rowSpacing: CGFloat
    let rowInsets: UIEdgeInsets

    static func make(lines: [UnifiedDiffLine]) -> UnifiedDiffLayout {
        let numberFont = UIFont.monospacedSystemFont(
            ofSize: UIFont.preferredFont(forTextStyle: .caption2).pointSize,
            weight: .regular
        )
        let indicatorFont = UIFont.monospacedSystemFont(
            ofSize: UIFont.preferredFont(forTextStyle: .caption2).pointSize,
            weight: .semibold
        )
        let codeFont = UIFont.monospacedSystemFont(
            ofSize: UIFont.preferredFont(forTextStyle: .footnote).pointSize,
            weight: .regular
        )
        let headerFont = UIFont.monospacedSystemFont(
            ofSize: UIFont.preferredFont(forTextStyle: .caption1).pointSize,
            weight: .regular
        )

        let maxDigits = max(
            lines.compactMap { $0.oldLine }.map { String($0).count }.max() ?? 1,
            lines.compactMap { $0.newLine }.map { String($0).count }.max() ?? 1
        )

        let width = columnWidth(digits: maxDigits, font: numberFont)
        return UnifiedDiffLayout(
            numberFont: numberFont,
            indicatorFont: indicatorFont,
            codeFont: codeFont,
            headerFont: headerFont,
            oldColumnWidth: width,
            newColumnWidth: width,
            indicatorWidth: 10,
            rowSpacing: 6,
            rowInsets: UIEdgeInsets(top: 4, left: 8, bottom: 4, right: 8)
        )
    }

    private static func columnWidth(digits: Int, font: UIFont) -> CGFloat {
        let d = max(1, digits)
        let sample = String(repeating: "0", count: d) as NSString
        let measured = ceil(sample.size(withAttributes: [.font: font]).width)
        // Keep gutter compact but readable across typical line ranges.
        return min(max(measured + 6, 18), 44)
    }
}

final class UnifiedDiffLineRowView: UIView {
    private let oldLabel = UILabel()
    private let newLabel = UILabel()
    private let indicatorLabel = UILabel()
    private let codeLabel = UILabel()

    private let row = UIStackView()

    private var oldWidthConstraint: NSLayoutConstraint?
    private var newWidthConstraint: NSLayoutConstraint?
    private var indicatorWidthConstraint: NSLayoutConstraint?

    private var layout: UnifiedDiffLayout = UnifiedDiffLayout.make(lines: [])

    override init(frame: CGRect) {
        super.init(frame: frame)
        setUp()
        applyLayout(layout)
    }

    convenience init(layout: UnifiedDiffLayout) {
        self.init(frame: .zero)
        applyLayout(layout)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func applyLayout(_ layout: UnifiedDiffLayout) {
        self.layout = layout

        row.spacing = layout.rowSpacing
        row.layoutMargins = layout.rowInsets

        oldLabel.font = layout.numberFont
        newLabel.font = layout.numberFont
        indicatorLabel.font = layout.indicatorFont
        codeLabel.font = layout.codeFont

        oldWidthConstraint?.constant = layout.oldColumnWidth
        newWidthConstraint?.constant = layout.newColumnWidth
        indicatorWidthConstraint?.constant = layout.indicatorWidth
    }

    func configure(line: UnifiedDiffLine) {
        oldLabel.text = line.oldLine.map(String.init) ?? ""
        newLabel.text = line.newLine.map(String.init) ?? ""
        let isHeader = line.kind == .hunkHeader || line.kind == .fileHeader || line.kind == .meta

        switch line.kind {
        case .addition:
            indicatorLabel.text = "+"
            indicatorLabel.textColor = UIColor.systemGreen
            backgroundColor = UIColor.systemGreen.withAlphaComponent(0.10)
        case .deletion:
            indicatorLabel.text = "−"
            indicatorLabel.textColor = UIColor.systemRed
            backgroundColor = UIColor.systemRed.withAlphaComponent(0.10)
        case .hunkHeader:
            indicatorLabel.text = ""
            indicatorLabel.textColor = .secondaryLabel
            backgroundColor = UIColor.tertiarySystemFill.withAlphaComponent(0.8)
        case .fileHeader, .meta:
            indicatorLabel.text = ""
            indicatorLabel.textColor = .secondaryLabel
            backgroundColor = UIColor.secondarySystemBackground.withAlphaComponent(0.6)
        case .context:
            indicatorLabel.text = ""
            indicatorLabel.textColor = .secondaryLabel
            backgroundColor = .clear
        }

        codeLabel.text = line.text
        codeLabel.font = isHeader ? layout.headerFont : layout.codeFont
        codeLabel.textColor = isHeader ? .secondaryLabel : .label

        oldLabel.isHidden = isHeader
        newLabel.isHidden = isHeader
        indicatorLabel.isHidden = isHeader
        oldWidthConstraint?.constant = isHeader ? 0 : layout.oldColumnWidth
        newWidthConstraint?.constant = isHeader ? 0 : layout.newColumnWidth
        indicatorWidthConstraint?.constant = isHeader ? 0 : layout.indicatorWidth
    }

    private func setUp() {
        clipsToBounds = true

        row.axis = .horizontal
        row.alignment = .top
        row.isLayoutMarginsRelativeArrangement = true

        oldLabel.textColor = .tertiaryLabel
        oldLabel.textAlignment = .right
        oldLabel.setContentHuggingPriority(.required, for: .horizontal)
        oldWidthConstraint = oldLabel.widthAnchor.constraint(equalToConstant: layout.oldColumnWidth)
        oldWidthConstraint?.isActive = true

        newLabel.textColor = .tertiaryLabel
        newLabel.textAlignment = .right
        newLabel.setContentHuggingPriority(.required, for: .horizontal)
        newWidthConstraint = newLabel.widthAnchor.constraint(equalToConstant: layout.newColumnWidth)
        newWidthConstraint?.isActive = true

        indicatorLabel.textColor = .secondaryLabel
        indicatorLabel.textAlignment = .center
        indicatorLabel.setContentHuggingPriority(.required, for: .horizontal)
        indicatorWidthConstraint = indicatorLabel.widthAnchor.constraint(equalToConstant: layout.indicatorWidth)
        indicatorWidthConstraint?.isActive = true

        codeLabel.numberOfLines = 0
        // Prefer word wrapping to avoid ugly mid-word splits in prose-ish diffs.
        codeLabel.lineBreakMode = .byWordWrapping

        row.addArrangedSubview(oldLabel)
        row.addArrangedSubview(newLabel)
        row.addArrangedSubview(indicatorLabel)
        row.addArrangedSubview(codeLabel)

        addSubview(row)
        row.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            row.topAnchor.constraint(equalTo: topAnchor),
            row.leadingAnchor.constraint(equalTo: leadingAnchor),
            row.trailingAnchor.constraint(equalTo: trailingAnchor),
            row.bottomAnchor.constraint(equalTo: bottomAnchor),
        ])
    }
}

final class UnifiedDiffLineCell: UITableViewCell {
    static let reuseIdentifier = "UnifiedDiffLineCell"

    private let rowView = UnifiedDiffLineRowView()

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        setUp()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func configure(line: UnifiedDiffLine, layout: UnifiedDiffLayout) {
        rowView.applyLayout(layout)
        rowView.configure(line: line)
    }

    private func setUp() {
        selectionStyle = .none
        backgroundColor = .clear
        contentView.backgroundColor = .clear

        contentView.addSubview(rowView)
        rowView.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            rowView.topAnchor.constraint(equalTo: contentView.topAnchor),
            rowView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
            rowView.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
            rowView.bottomAnchor.constraint(equalTo: contentView.bottomAnchor),
        ])
    }
}
