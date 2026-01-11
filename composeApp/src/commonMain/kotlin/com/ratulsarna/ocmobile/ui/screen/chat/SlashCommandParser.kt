package com.ratulsarna.ocmobile.ui.screen.chat

data class SlashCommandInvocation(
    val name: String,
    val arguments: String
)

data class SlashCommandAutocomplete(
    val commandStartIndex: Int,
    val query: String
)

/**
 * Parse a slash command invocation like `/review my-branch`.
 *
 * Returns null when input is not a slash command (or is just `/`).
 *
 * Note: the OpenCode server handles command existence/validation; callers typically
 * check the parsed name against the server-provided command list before treating it
 * as a command.
 */
fun parseSlashCommandInvocation(text: String): SlashCommandInvocation? {
    // Only treat as a slash command when "/" is the first character of the message.
    // Trailing whitespace/newlines are ignored.
    val trimmed = text.trimEnd()
    if (!trimmed.startsWith("/")) return null
    if (trimmed == "/") return null

    val firstDelimiter = trimmed.indexOfAny(charArrayOf(' ', '\t', '\n', '\r'))
    val token = if (firstDelimiter == -1) trimmed else trimmed.substring(0, firstDelimiter)
    val name = token.drop(1)
    if (name.isBlank()) return null

    val arguments = if (firstDelimiter == -1) "" else trimmed.substring(firstDelimiter + 1)
    return SlashCommandInvocation(name = name, arguments = arguments)
}

/**
 * Detect whether the user is currently typing a slash command (for autocomplete UI).
 *
 * Current behavior mirrors the OpenCode TUI prompt:
 * - Slash commands are only recognized when the message starts with "/"
 * - Autocomplete remains active while the cursor is within the first token (before first whitespace)
 *
 * Returns null if the input/cursor should not show the slash-command popup.
 */
fun detectSlashCommandAutocomplete(text: String, cursorPosition: Int): SlashCommandAutocomplete? {
    if (text.isEmpty()) return null
    if (text[0] != '/') return null
    if (cursorPosition <= 0) return null

    val tokenEnd = text.indexOfAny(charArrayOf(' ', '\t', '\n', '\r')).let { if (it == -1) text.length else it }
    if (cursorPosition > tokenEnd) return null

    val query = text.substring(1, cursorPosition)
    return SlashCommandAutocomplete(
        commandStartIndex = 0,
        query = query
    )
}
