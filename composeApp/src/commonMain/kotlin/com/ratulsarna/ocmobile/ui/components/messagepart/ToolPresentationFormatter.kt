package com.ratulsarna.ocmobile.ui.components.messagepart

import com.ratulsarna.ocmobile.domain.model.ToolState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

object ToolPresentationFormatter {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private const val charsPerPreviewLine = 80

    fun format(
        tool: String,
        state: ToolState,
        inputJson: String?,
        output: String?,
        error: String?,
        titleFromServer: String?,
        metadataJson: String?,
        maxPreviewLines: Int = 4
    ): ToolPresentation {
        val toolId = tool.trim()
        val toolLower = toolId.lowercase()
        val input = parseJsonObject(inputJson)
        val metadata = parseJsonObject(metadataJson)

        val description = input?.stringOrNull("description")?.takeUnless { it.isBlank() }
        val filePath = input?.stringOrNull("filePath")?.takeUnless { it.isBlank() }
        val path = input?.stringOrNull("path")?.takeUnless { it.isBlank() }
        val pattern = input?.stringOrNull("pattern")?.takeUnless { it.isBlank() }
        val url = input?.stringOrNull("url")?.takeUnless { it.isBlank() }
        val query = input?.stringOrNull("query")?.takeUnless { it.isBlank() }

        val title = when (toolLower) {
            "bash" -> description ?: toolId.ifBlank { "bash" }
            "write", "read", "edit", "multiedit", "lsp_diagnostics", "lsp_hover", "lsp-diagnostics", "lsp-hover" -> {
                filePath?.let(::lastPathComponent)
                    ?: titleFromServer?.takeUnless { it.isBlank() }
                    ?: toolId.ifBlank { toolLower.ifBlank { "tool" } }
            }
            "patch" -> titleFromServer?.takeUnless { it.isBlank() } ?: toolId.ifBlank { "patch" }
            "list", "ls" -> path?.let(::lastPathComponent) ?: toolId.ifBlank { "list" }
            "glob", "grep" -> pattern ?: toolId
            "webfetch" -> url?.let(::formatUrlForTitle) ?: toolId
            "websearch", "codesearch" -> query ?: toolId
            "todowrite", "todoread" -> titleFromServer?.takeUnless { it.isBlank() } ?: "To-dos"
            "task" -> description ?: titleFromServer?.takeUnless { it.isBlank() } ?: toolId
            "batch" -> titleFromServer?.takeUnless { it.isBlank() } ?: "Batch"
            else -> description ?: titleFromServer?.takeUnless { it.isBlank() } ?: toolId.ifBlank { "tool" }
        }

        val blocks = buildList {
            when (toolLower) {
                "bash" -> {
                    val command = input?.stringOrNull("command")?.takeUnless { it.isBlank() }
                    if (command != null) {
                        add(ToolPresentationBlock.Code("Command", previewLines(command, maxPreviewLines)))
                    }
                    val out = output?.takeUnless { it.isBlank() }
                    if (out != null) {
                        add(ToolPresentationBlock.Code("Output", previewLines(out, maxPreviewLines)))
                    }
                }
                "write" -> {
                    if (filePath != null) {
                        add(ToolPresentationBlock.File(path = filePath))
                    }
                    val content = input?.stringOrNull("content")?.takeUnless { it.isBlank() }
                    if (content != null) {
                        add(ToolPresentationBlock.Code("Content", previewLines(content, maxPreviewLines)))
                    }
                    val out = output?.takeUnless { it.isBlank() }
                    if (out != null) {
                        add(ToolPresentationBlock.Code("Output", previewLines(out, maxPreviewLines)))
                    }
                }
                "read" -> {
                    if (filePath != null) {
                        add(ToolPresentationBlock.File(path = filePath))
                    }
                    val out = output
                        ?.let(::stripReadToolTagsIfPresent)
                        ?.let(::stripReadToolLinePrefixesIfPresent)
                        ?.takeUnless { it.isBlank() }
                    if (out != null) {
                        add(ToolPresentationBlock.Code("Output", previewLines(out, maxPreviewLines)))
                    }
                }
                "edit", "multiedit" -> {
                    if (filePath != null) {
                        add(ToolPresentationBlock.File(path = filePath))
                    }
                    val diff = metadata?.stringOrNull("diff")?.takeUnless { it.isBlank() }
                    if (diff != null) {
                        add(ToolPresentationBlock.Diff(text = previewDiffLines(diff, maxPreviewLines)))
                    }
                    val out = output?.takeUnless { it.isBlank() }
                    if (out != null) {
                        add(ToolPresentationBlock.Code("Output", previewLines(out, maxPreviewLines)))
                    }
                }
                "patch" -> {
                    val diff = metadata?.stringOrNull("diff")?.takeUnless { it.isBlank() }
                    if (diff != null) {
                        add(ToolPresentationBlock.Diff(text = previewDiffLines(diff, maxPreviewLines)))
                    }
                    val out = output?.takeUnless { it.isBlank() }
                    if (out != null) {
                        add(ToolPresentationBlock.Code("Output", previewLines(out, maxPreviewLines)))
                    }
                }
                "todowrite", "todoread" -> {
                    val todosText = formatTodosFromMetadataOrOutput(metadata = metadata, output = output)
                    if (todosText != null) {
                        add(ToolPresentationBlock.Code("To-dos", previewLines(todosText, maxPreviewLines)))
                    }
                }
                "list", "ls", "glob", "grep", "webfetch", "websearch", "codesearch", "task", "batch", "lsp_diagnostics", "lsp_hover", "lsp-diagnostics", "lsp-hover" -> {
                    if (filePath != null) {
                        add(ToolPresentationBlock.File(path = filePath))
                    }
                    val params = input?.toKeyValues()
                    if (!params.isNullOrEmpty()) {
                        add(ToolPresentationBlock.KeyValues(items = params))
                    }
                    val out = output?.takeUnless { it.isBlank() }
                    if (out != null) {
                        add(ToolPresentationBlock.Code("Output", previewLines(out, maxPreviewLines)))
                    }
                }
                else -> {
                    if (filePath != null) {
                        add(ToolPresentationBlock.File(path = filePath))
                    }
                    val params = input?.toKeyValues()
                    if (!params.isNullOrEmpty()) {
                        add(ToolPresentationBlock.KeyValues(items = params))
                    }
                    val out = output?.takeUnless { it.isBlank() }
                    if (out != null) {
                        add(ToolPresentationBlock.Code("Output", previewLines(out, maxPreviewLines)))
                    }
                }
            }

            if (!error.isNullOrBlank()) {
                add(ToolPresentationBlock.Error(error))
            } else if (state == ToolState.ERROR) {
                // Defensive: some servers may omit error for error states.
                add(ToolPresentationBlock.Error("Tool failed"))
            }
        }

        return ToolPresentation(
            title = title,
            blocks = blocks
        )
    }

    internal fun previewLines(text: String, maxLines: Int): ToolTextPreview {
        val maxChars = maxLines * charsPerPreviewLine
        val targetLines = maxOf(1, maxLines)

        var lineCount = 1
        var truncatedByLines = false
        var truncatedByChars = false

        val buffer = StringBuilder(minOf(text.length, maxChars + 8))

        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '\n') {
                if (lineCount >= targetLines) {
                    // Only consider this "more lines" if there's any non-newline content after it.
                    var j = i + 1
                    while (j < text.length && (text[j] == '\n' || text[j] == '\r')) {
                        j += 1
                    }
                    truncatedByLines = j < text.length
                    break
                }
                if (buffer.length >= maxChars + 1) {
                    truncatedByChars = true
                    break
                }
                buffer.append('\n')
                lineCount += 1
                i += 1
                continue
            }

            if (buffer.length >= maxChars + 1) {
                truncatedByChars = true
                break
            }

            buffer.append(ch)
            i += 1
        }

        var preview = buffer.toString()
        if (preview.length > maxChars) {
            preview = preview.take(maxChars)
            truncatedByChars = true
        }
        if (truncatedByChars) {
            preview += "…"
        }

        val isTruncated = truncatedByLines || truncatedByChars
        return ToolTextPreview(
            previewText = preview,
            fullText = text,
            isTruncated = isTruncated
        )
    }

    internal fun previewDiffLines(diff: String, maxLines: Int): ToolTextPreview {
        val targetLines = maxOf(1, maxLines)

        val changeLines = ArrayList<String>(targetLines + 1)
        val fallbackFromStart = ArrayList<String>(targetLines + 1)
        val fallbackFromFirstHunk = ArrayList<String>(targetLines + 1)

        var inHunk = false
        var sawFirstHunk = false

        fun isPlusFileHeaderLine(line: String): Boolean = line.startsWith("+++ ") || line.startsWith("+++\t")
        fun isMinusFileHeaderLine(line: String): Boolean = line.startsWith("--- ") || line.startsWith("---\t")

        for (line in diff.lineSequence()) {
            if (fallbackFromStart.size < targetLines + 1) {
                fallbackFromStart.add(line)
            }

            if (line.startsWith("Index:") || line.startsWith("===") || line.startsWith("diff ") || line.startsWith("index ")) {
                inHunk = false
            }
            if (line.startsWith("@@")) {
                inHunk = true
                if (!sawFirstHunk) {
                    sawFirstHunk = true
                }
            }

            if (sawFirstHunk && fallbackFromFirstHunk.size < targetLines + 1) {
                fallbackFromFirstHunk.add(line)
            }

            val isAddition = line.startsWith("+") && (inHunk || !isPlusFileHeaderLine(line))
            val isDeletion = line.startsWith("-") && (inHunk || !isMinusFileHeaderLine(line))
            if (isAddition || isDeletion) {
                if (changeLines.size < targetLines + 1) {
                    changeLines.add(line)
                }
            }

            // We have enough change lines to build a preview; avoid scanning huge diffs unnecessarily.
            if (changeLines.size >= targetLines + 1 && fallbackFromStart.size >= targetLines + 1) {
                break
            }
        }

        val source = when {
            changeLines.isNotEmpty() -> changeLines
            fallbackFromFirstHunk.isNotEmpty() -> fallbackFromFirstHunk
            else -> fallbackFromStart
        }

        val preview = source.take(targetLines).joinToString("\n")
        val diffHasMoreThanMaxLines = fallbackFromStart.size > targetLines
        val isTruncated = diffHasMoreThanMaxLines || source.size > targetLines
        return ToolTextPreview(
            previewText = preview,
            fullText = diff,
            isTruncated = isTruncated
        )
    }

    internal fun parseJsonObject(raw: String?): JsonObject? {
        if (raw.isNullOrBlank()) return null
        return runCatching { json.parseToJsonElement(raw) }
            .getOrNull()
            ?.let { it as? JsonObject }
    }

    internal fun parseJsonElement(raw: String?): JsonElement? {
        if (raw.isNullOrBlank()) return null
        return runCatching { json.parseToJsonElement(raw) }.getOrNull()
    }

    internal fun stringifyValue(value: JsonElement): String {
        return when (value) {
            is JsonPrimitive -> {
                value.contentOrNull ?: ""
            }
            is JsonArray -> "[${value.size} items]"
            is JsonObject -> "{${value.size} keys}"
        }
    }

    private fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.toKeyValues(): List<ToolKeyValue> {
        return entries
            .sortedBy { it.key }
            .map { (key, value) ->
                ToolKeyValue(
                    key = key,
                    value = stringifyValue(value)
                )
            }
    }

    private fun lastPathComponent(path: String): String {
        val sanitized = path.trimEnd('/', '\\')
        val idxSlash = sanitized.lastIndexOf('/')
        val idxBackslash = sanitized.lastIndexOf('\\')
        val idx = maxOf(idxSlash, idxBackslash)
        return if (idx >= 0 && idx < sanitized.length - 1) sanitized.substring(idx + 1) else sanitized
    }

    private fun stripReadToolTagsIfPresent(text: String): String {
        // OpenCode read tool can wrap output as "<file> ... </file>". That wrapper is model-facing, not user-facing.
        if (text.isBlank()) return text

        var start = 0
        var end = text.length

        while (start < end && text[start] == '\n') start += 1
        while (start < end && text[end - 1] == '\n') end -= 1
        if (start >= end) return ""

        val firstLineEnd = run {
            val idx = text.indexOf('\n', startIndex = start)
            if (idx == -1 || idx >= end) end else idx
        }
        val firstLine = text.substring(start, firstLineEnd).trimEnd('\r')
        if (firstLine.trim() == "<file>") {
            start = (firstLineEnd + 1).coerceAtMost(end)
            while (start < end && text[start] == '\n') start += 1
        }
        if (start >= end) return ""

        val lastNewline = run {
            val idx = text.lastIndexOf('\n', startIndex = end - 1)
            if (idx < start) -1 else idx
        }
        val lastLineStart = if (lastNewline == -1) start else lastNewline + 1
        val lastLine = text.substring(lastLineStart, end).trimEnd('\r')
        if (lastLine.trim() == "</file>") {
            end = if (lastNewline == -1) start else lastNewline
            while (start < end && text[end - 1] == '\n') end -= 1
        }
        if (start >= end) return ""

        return text.substring(start, end).trim('\n')
    }

    private fun stripReadToolLinePrefixesIfPresent(text: String): String {
        // OpenCode read tool prefixes each line as "00001| ...". That is useful for the model, but noisy in UI.
        return buildString(text.length) {
            var first = true
            for (line in text.lineSequence()) {
                if (!first) append('\n')
                first = false

                append(stripReadLinePrefix(line))
            }
        }
    }

    private fun stripReadLinePrefix(line: String): String {
        if (line.length < 6) return line
        if (line[5] != '|') return line
        for (i in 0 until 5) {
            if (!line[i].isDigit()) return line
        }
        val start = if (line.length > 6 && line[6] == ' ') 7 else 6
        return if (start <= line.length) line.substring(start) else ""
    }

    private fun formatTodosFromMetadataOrOutput(
        metadata: JsonObject?,
        output: String?
    ): String? {
        val todosElement = metadata?.get("todos")
            ?: parseJsonElement(output)
        val todos = todosElement as? JsonArray ?: return null

        val lines = buildList {
            for (item in todos) {
                val obj = item as? JsonObject ?: continue
                val content = (obj["content"] as? JsonPrimitive)?.contentOrNull ?: continue
                val status = (obj["status"] as? JsonPrimitive)?.contentOrNull ?: ""
                val checked = status == "completed"
                add("${if (checked) "[x]" else "[ ]"} $content")
            }
        }

        if (lines.isEmpty()) return null
        return lines.joinToString("\n")
    }

    private fun formatUrlForTitle(raw: String): String {
        val trimmed = raw.trim()
        val withoutScheme = trimmed.replace(Regex("^https?://", RegexOption.IGNORE_CASE), "")
        val withoutWww = withoutScheme.replace(Regex("^www\\.", RegexOption.IGNORE_CASE), "")
        return withoutWww.removeSuffix("/")
    }
}
