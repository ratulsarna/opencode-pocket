package com.ratulsarna.ocmobile.ui.components.markdown

const val FILE_LINK_PREFIX = "oc-pocket-file:"

fun linkifyFilePaths(text: String): String {
    if (text.isBlank()) return text

    return transformMarkdownTextPreservingCodeFences(text) { line ->
        transformLinePreservingInlineCodeSpans(
            line = line,
            transformOutsideInlineCode = { segment ->
                transformTextPreservingMarkdownLinks(segment, ::linkifyPlainSegment)
            },
            transformInlineCodeSpan = { codeContent, delimiter ->
                val trimmed = codeContent.trim()
                if (trimmed.isNotEmpty() &&
                    trimmed == codeContent &&
                    isValidPath(trimmed) &&
                    !trimmed.substringBefore('/').any(Char::isWhitespace)
                ) {
                    "[$trimmed](${FILE_LINK_PREFIX}${encodeFilePath(trimmed)})"
                } else {
                    delimiter + codeContent + delimiter
                }
            }
        )
    }
}

internal fun encodeFilePath(path: String): String = buildString(path.length) {
    val bytes = path.encodeToByteArray()
    for (byte in bytes) {
        val value = byte.toInt() and 0xFF
        val ch = value.toChar()
        if (ch == '/' || ch.isUnreserved()) {
            append(ch)
        } else {
            append('%')
            append(HEX[value shr 4])
            append(HEX[value and 0x0F])
        }
    }
}

internal fun decodeFilePath(encoded: String): String {
    if (!encoded.contains('%')) return encoded

    val bytes = ByteArray(encoded.length)
    var byteIndex = 0
    var index = 0
    while (index < encoded.length) {
        val ch = encoded[index]
        if (ch == '%' && index + 2 < encoded.length) {
            val hi = encoded[index + 1].hexValue()
            val lo = encoded[index + 2].hexValue()
            if (hi != null && lo != null) {
                bytes[byteIndex++] = ((hi shl 4) or lo).toByte()
                index += 3
                continue
            }
        }
        bytes[byteIndex++] = ch.code.toByte()
        index += 1
    }

    return bytes.copyOf(byteIndex).decodeToString()
}

internal fun decodeFileLink(link: String): String? {
    if (!link.startsWith(FILE_LINK_PREFIX)) return null
    return decodeFilePath(link.removePrefix(FILE_LINK_PREFIX))
}

private fun linkifyPlainSegment(segment: String): String {
    if (segment.isBlank()) return segment

    val result = StringBuilder(segment.length)
    var index = 0

    while (index < segment.length) {
        val start = findNextPathStart(segment, index)
        if (start == -1) {
            result.append(segment.substring(index))
            break
        }

        result.append(segment.substring(index, start))

        val match = extractPathMatch(segment, start)
        if (match == null) {
            result.append(segment[start])
            index = start + 1
            continue
        }

        result.append('[')
        result.append(match.path)
        result.append("](")
        result.append(FILE_LINK_PREFIX)
        result.append(encodeFilePath(match.path))
        result.append(')')
        result.append(match.trailing)
        index = match.end
    }

    return result.toString()
}

private fun findNextPathStart(text: String, startIndex: Int): Int {
    var index = startIndex
    while (index < text.length) {
        val ch = text[index]
        if (isPotentialPathStart(ch) && isBoundary(text, index)) {
            val tokenEnd = text.indexOf(' ', index).takeIf { it != -1 } ?: text.length
            val token = text.substring(index, tokenEnd)
            if (token.contains('/')) {
                return index
            }
        }
        index += 1
    }
    return -1
}

private fun isPotentialPathStart(ch: Char): Boolean =
    ch.isLetterOrDigit() || ch == '/' || ch == '.'

private fun isBoundary(text: String, index: Int): Boolean {
    if (index == 0) return true
    val prev = text[index - 1]
    return prev.isWhitespace() || prev in PATH_BOUNDARY_CHARS
}

private data class PathMatch(
    val path: String,
    val trailing: String,
    val end: Int,
)

private fun extractPathMatch(text: String, start: Int): PathMatch? {
    var index = start
    var matchEnd: Int? = null

    while (index < text.length && isAllowedPathChar(text[index])) {
        val end = index + 1
        val raw = text.substring(start, end)
        val trimmed = trimTrailingPunctuation(raw)
        if (trimmed.isNotEmpty() && isValidPath(trimmed) && isEndBoundary(text, end)) {
            matchEnd = end
            break
        }
        index += 1
    }

    val endIndex = matchEnd ?: return null
    val raw = text.substring(start, endIndex)
    val trimmed = trimTrailingPunctuation(raw)
    val trailing = raw.removePrefix(trimmed)
    return PathMatch(trimmed, trailing, endIndex)
}

private fun isAllowedPathChar(ch: Char): Boolean {
    if (ch.isLetterOrDigit()) return true
    if (ch.category == CharCategory.DASH_PUNCTUATION) return true
    return ch == '/' || ch == '.' || ch == '_' || ch == '-' || ch == ' ' || ch == ',' || ch == '(' || ch == ')'
}

private fun trimTrailingPunctuation(value: String): String {
    var end = value.length
    while (end > 0 && value[end - 1] in TRAILING_PUNCTUATION) {
        end -= 1
    }
    return value.substring(0, end)
}

private fun isValidPath(candidate: String): Boolean {
    if (!candidate.contains('/')) return false
    if (candidate.contains("://")) return false

    val lastSlash = candidate.lastIndexOf('/')
    if (lastSlash == candidate.length - 1 || lastSlash == -1) return false

    val fileName = candidate.substring(lastSlash + 1)
    val dot = fileName.lastIndexOf('.')
    if (dot <= 0 || dot == fileName.length - 1) return false

    val extension = fileName.substring(dot + 1)
    if (extension.length > 10) return false
    if (!extension.all { it.isLetterOrDigit() }) return false

    return true
}

private val PATH_BOUNDARY_CHARS = setOf('(', '[', '{', '"', '\'')

private val TRAILING_PUNCTUATION = setOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '"', '\'')

private fun isEndBoundary(text: String, endIndex: Int): Boolean {
    if (endIndex >= text.length) return true
    val next = text[endIndex]
    return next.isWhitespace() || next in PATH_END_BOUNDARY_CHARS
}

private val PATH_END_BOUNDARY_CHARS = setOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '"', '\'')

private val HEX = "0123456789ABCDEF".toCharArray()

private fun Char.isUnreserved(): Boolean =
    this.isLetterOrDigit() || this == '-' || this == '_' || this == '.' || this == '~'

private fun Char.hexValue(): Int? = when (this) {
    in '0'..'9' -> this - '0'
    in 'a'..'f' -> this - 'a' + 10
    in 'A'..'F' -> this - 'A' + 10
    else -> null
}
