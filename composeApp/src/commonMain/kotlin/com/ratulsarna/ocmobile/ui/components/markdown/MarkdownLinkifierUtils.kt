package com.ratulsarna.ocmobile.ui.components.markdown

/**
 * Shared scanning utilities for linkifying plain text inside Markdown.
 *
 * These helpers are intentionally conservative: they avoid rewriting fenced code blocks, inline code spans
 * (unless the caller opts into rewriting those spans), reference definitions, and existing Markdown links.
 */
internal fun transformMarkdownTextPreservingCodeFences(
    text: String,
    transformLineOutsideFences: (String) -> String
): String {
    if (text.isBlank()) return text

    // Preserve trailing newlines (default `split` drops trailing empty segments when `limit == 0`).
    val lines = text.split('\n', limit = Int.MAX_VALUE)
    val result = StringBuilder(text.length)
    var inFence = false
    var fenceMarker: String? = null

    lines.forEachIndexed { index, line ->
        val trimmed = line.trimStart()
        val fenceCandidate = stripBlockQuotePrefix(trimmed).trimStart()
        val marker = fenceMarkerFor(fenceCandidate)
        when {
            marker != null && !inFence -> {
                inFence = true
                fenceMarker = marker
                result.append(line)
            }
            inFence && fenceMarker != null && isClosingFenceLine(fenceCandidate, fenceMarker!!) -> {
                inFence = false
                fenceMarker = null
                result.append(line)
            }
            inFence -> result.append(line)
            isReferenceDefinitionLine(fenceCandidate) -> result.append(line)
            else -> result.append(transformLineOutsideFences(line))
        }

        if (index != lines.lastIndex) {
            result.append('\n')
        }
    }

    return result.toString()
}

internal fun transformLinePreservingInlineCodeSpans(
    line: String,
    transformOutsideInlineCode: (String) -> String,
    transformInlineCodeSpan: (codeContent: String, delimiter: String) -> String
): String {
    val result = StringBuilder(line.length)
    var index = 0

    while (index < line.length) {
        val start = line.indexOf('`', index)
        if (start == -1) {
            result.append(transformOutsideInlineCode(line.substring(index)))
            break
        }

        val run = backtickRun(line, start)
        val end = line.indexOf(run, start + run.length)
        if (end == -1) {
            result.append(transformOutsideInlineCode(line.substring(index, start)))
            result.append(line.substring(start))
            break
        }

        result.append(transformOutsideInlineCode(line.substring(index, start)))
        val codeContent = line.substring(start + run.length, end)
        result.append(transformInlineCodeSpan(codeContent, run))
        index = end + run.length
    }

    return result.toString()
}

internal fun transformTextPreservingMarkdownLinks(
    segment: String,
    transformPlainSegment: (String) -> String
): String {
    if (segment.isBlank()) return segment

    val result = StringBuilder(segment.length)
    var index = 0

    while (index < segment.length) {
        val linkStart = findMarkdownLinkStart(segment, index)
        if (linkStart == -1) {
            result.append(transformPlainSegment(segment.substring(index)))
            break
        }

        val linkEnd = findMarkdownLinkEnd(segment, linkStart)
        if (linkEnd == null) {
            result.append(transformPlainSegment(segment.substring(index, linkStart + 1)))
            index = linkStart + 1
            continue
        }

        result.append(transformPlainSegment(segment.substring(index, linkStart)))
        result.append(segment.substring(linkStart, linkEnd))
        index = linkEnd
    }

    return result.toString()
}

internal fun backtickRun(line: String, start: Int): String {
    var end = start
    while (end < line.length && line[end] == '`') {
        end += 1
    }
    return line.substring(start, end)
}

internal fun fenceMarkerFor(trimmedLine: String): String? {
    if (trimmedLine.isEmpty()) return null
    val first = trimmedLine.first()
    if (first != '`' && first != '~') return null
    val marker = trimmedLine.takeWhile { it == first }
    if (marker.length < 3) return null

    // CommonMark: for backtick fences, the info string may not contain backticks.
    // This also prevents misclassifying inline code spans like "```foo```" as a fenced block.
    if (first == '`') {
        val rest = trimmedLine.substring(marker.length)
        if (rest.contains('`')) return null
    }

    return marker
}

internal fun stripBlockQuotePrefix(trimmedLine: String): String {
    var current = trimmedLine
    while (current.startsWith('>')) {
        current = current.drop(1)
        if (current.startsWith(' ') || current.startsWith('\t')) {
            current = current.drop(1)
        }
    }
    return current
}

internal fun isReferenceDefinitionLine(trimmedLine: String): Boolean {
    if (!trimmedLine.startsWith('[')) return false
    val labelEnd = findClosing(trimmedLine, 0, '[', ']') ?: return false
    var index = labelEnd
    while (index < trimmedLine.length && trimmedLine[index].isWhitespace()) {
        index += 1
    }
    return index < trimmedLine.length && trimmedLine[index] == ':'
}

internal fun isClosingFenceLine(trimmedLine: String, openingMarker: String): Boolean {
    if (trimmedLine.isEmpty()) return false
    val fenceChar = openingMarker.first()
    if (trimmedLine.first() != fenceChar) return false

    var runLength = 0
    while (runLength < trimmedLine.length && trimmedLine[runLength] == fenceChar) {
        runLength += 1
    }

    if (runLength < openingMarker.length) return false
    return trimmedLine.substring(runLength).isBlank()
}

internal fun findMarkdownLinkStart(text: String, startIndex: Int): Int {
    var index = startIndex
    while (index < text.length) {
        if (text[index] == '[' && !isEscaped(text, index)) {
            return index
        }
        index += 1
    }
    return -1
}

internal fun findMarkdownLinkEnd(text: String, startIndex: Int): Int? {
    val labelEnd = findClosing(text, startIndex, '[', ']') ?: return null
    var index = labelEnd
    while (index < text.length && text[index].isWhitespace()) {
        index += 1
    }
    if (index >= text.length) return null
    val open = text[index]
    return when (open) {
        '(' -> findClosing(text, index, '(', ')')
        '[' -> findClosing(text, index, '[', ']')
        else -> null
    }
}

internal fun findClosing(text: String, startIndex: Int, open: Char, close: Char): Int? {
    var depth = 0
    var index = startIndex
    while (index < text.length) {
        val ch = text[index]
        if (ch == '\\') {
            index += 2
            continue
        }
        if (ch == open) {
            depth += 1
        } else if (ch == close) {
            depth -= 1
            if (depth == 0) return index + 1
        }
        index += 1
    }
    return null
}

internal fun isEscaped(text: String, index: Int): Boolean {
    var slashCount = 0
    var cursor = index - 1
    while (cursor >= 0 && text[cursor] == '\\') {
        slashCount += 1
        cursor -= 1
    }
    return slashCount % 2 == 1
}

