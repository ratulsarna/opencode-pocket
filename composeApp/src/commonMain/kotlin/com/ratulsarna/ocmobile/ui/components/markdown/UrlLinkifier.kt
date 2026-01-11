package com.ratulsarna.ocmobile.ui.components.markdown

/**
 * Converts bare http/https URLs into Markdown link syntax so the Markdown renderer can make them clickable.
 *
 * This is intentionally conservative:
 * - does not rewrite fenced code blocks
 * - does not rewrite inline code spans
 * - does not rewrite existing markdown links
 * - ignores non-http(s) schemes (including `oc-pocket://`)
 */
fun linkifyUrls(text: String): String {
    if (text.isBlank()) return text

    return transformMarkdownTextPreservingCodeFences(text) { line ->
        transformLinePreservingInlineCodeSpans(
            line = line,
            transformOutsideInlineCode = { segment ->
                transformTextPreservingMarkdownLinks(segment, ::linkifyPlainSegment)
            },
            transformInlineCodeSpan = { codeContent, delimiter ->
                delimiter + codeContent + delimiter
            }
        )
    }
}

private fun linkifyPlainSegment(segment: String): String {
    if (segment.isBlank()) return segment

    val result = StringBuilder(segment.length)
    var index = 0

    while (index < segment.length) {
        val start = findNextUrlStart(segment, index)
        if (start == -1) {
            result.append(segment.substring(index))
            break
        }

        result.append(segment.substring(index, start))

        val match = extractUrlMatch(segment, start)
        if (match == null) {
            result.append(segment[start])
            index = start + 1
            continue
        }

        result.append('[')
        result.append(match.url)
        result.append("](")
        result.append(match.url)
        result.append(')')
        result.append(match.trailing)
        index = match.end
    }

    return result.toString()
}

private fun findNextUrlStart(text: String, startIndex: Int): Int {
    var index = startIndex
    while (index < text.length) {
        val httpStart = text.indexOf("http", index)
        if (httpStart == -1) return -1
        if (!isBoundary(text, httpStart)) {
            index = httpStart + 4
            continue
        }
        if (text.startsWith("http://", httpStart) || text.startsWith("https://", httpStart)) {
            return httpStart
        }
        index = httpStart + 4
    }
    return -1
}

private fun isBoundary(text: String, index: Int): Boolean {
    if (index == 0) return true
    val prev = text[index - 1]
    return prev.isWhitespace() || prev in URL_BOUNDARY_CHARS
}

private data class UrlMatch(
    val url: String,
    val trailing: String,
    val end: Int,
)

private fun extractUrlMatch(text: String, start: Int): UrlMatch? {
    var index = start
    while (index < text.length && !text[index].isWhitespace()) {
        index += 1
    }

    if (index == start) return null
    val raw = text.substring(start, index)
    if (!(raw.startsWith("http://") || raw.startsWith("https://"))) return null

    val (trimmed, trailing) = splitTrailingPunctuationKeepingBalancedParens(raw)
    if (trimmed.isBlank()) return null

    return UrlMatch(url = trimmed, trailing = trailing, end = index)
}

private fun splitTrailingPunctuationKeepingBalancedParens(raw: String): Pair<String, String> {
    var end = raw.length
    while (end > 0 && raw[end - 1] in URL_TRAILING_PUNCTUATION) {
        end -= 1
    }

    var core = raw.substring(0, end)
    var trailing = raw.substring(end)

    while (trailing.startsWith(')') && core.count { it == '(' } > core.count { it == ')' }) {
        core += ')'
        trailing = trailing.drop(1)
    }

    return core to trailing
}

private val URL_BOUNDARY_CHARS = setOf('(', '[', '{', '<', '"', '\'')

private val URL_TRAILING_PUNCTUATION = setOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '>', '"', '\'')
