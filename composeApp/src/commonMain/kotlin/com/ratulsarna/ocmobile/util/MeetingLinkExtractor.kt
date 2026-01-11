package com.ratulsarna.ocmobile.util

/**
 * Extracts the first Google Meet URL from arbitrary text.
 *
 * Notes:
 * - Only matches `https://meet.google.com/...` (conservatively require https).
 * - URL ends at whitespace; trailing punctuation is trimmed.
 * - Conservatively requires a word/segment boundary before the URL.
 */
fun extractFirstGoogleMeetUrl(text: String?): String? {
    if (text.isNullOrBlank()) return null

    var index = 0
    while (index < text.length) {
        val start = findNextMeetUrlStart(text, index)
        if (start == -1) return null

        val end = findUrlEnd(text, start)
        if (end <= start) return null

        val raw = text.substring(start, end)
        val trimmed = trimTrailingPunctuationKeepingBalancedParens(raw)
        if (trimmed.startsWith(MEET_URL_PREFIX) && trimmed.length > MEET_URL_PREFIX.length) {
            return trimmed
        }

        index = start + MEET_URL_PREFIX.length
    }

    return null
}

private const val MEET_URL_PREFIX = "https://meet.google.com/"

private fun findNextMeetUrlStart(text: String, startIndex: Int): Int {
    var index = startIndex
    while (index < text.length) {
        val found = text.indexOf(MEET_URL_PREFIX, index)
        if (found == -1) return -1
        if (isBoundary(text, found)) return found
        index = found + MEET_URL_PREFIX.length
    }
    return -1
}

private fun isBoundary(text: String, index: Int): Boolean {
    if (index == 0) return true
    val prev = text[index - 1]
    return prev.isWhitespace() || prev in URL_BOUNDARY_CHARS
}

private fun findUrlEnd(text: String, start: Int): Int {
    var index = start
    while (index < text.length && !text[index].isWhitespace()) {
        index += 1
    }
    return index
}

private fun trimTrailingPunctuationKeepingBalancedParens(raw: String): String {
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

    return core
}

private val URL_BOUNDARY_CHARS = setOf('(', '[', '{', '<', '"', '\'')

private val URL_TRAILING_PUNCTUATION = setOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '>', '"', '\'')
