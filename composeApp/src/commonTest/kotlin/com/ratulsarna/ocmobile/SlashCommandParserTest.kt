package com.ratulsarna.ocmobile

import com.ratulsarna.ocmobile.ui.screen.chat.detectSlashCommandAutocomplete
import com.ratulsarna.ocmobile.ui.screen.chat.parseSlashCommandInvocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class SlashCommandParserTest {
    @Test
    fun nonSlashText_returnsNull() {
        assertNull(parseSlashCommandInvocation("hello"))
        assertNull(parseSlashCommandInvocation("hello /review"))
    }

    @Test
    fun slashOnly_returnsNull() {
        assertNull(parseSlashCommandInvocation("/"))
        assertNull(parseSlashCommandInvocation("   /   "))
    }

    @Test
    fun leadingWhitespace_doesNotParseAsSlashCommand() {
        assertNull(parseSlashCommandInvocation(" /review"))
        assertNull(parseSlashCommandInvocation("\t/review"))
        assertNull(parseSlashCommandInvocation("\n/review"))
    }

    @Test
    fun commandWithoutArgs_parsesNameAndEmptyArgs() {
        val parsed = parseSlashCommandInvocation("/review")!!
        assertEquals("review", parsed.name)
        assertEquals("", parsed.arguments)
    }

    @Test
    fun commandWithArgs_preservesArgumentString() {
        val parsed = parseSlashCommandInvocation("/review foo bar")!!
        assertEquals("review", parsed.name)
        assertEquals("foo bar", parsed.arguments)

        val spaced = parseSlashCommandInvocation("/review   foo")!!
        assertEquals("review", spaced.name)
        assertEquals("  foo", spaced.arguments)

        val tabbed = parseSlashCommandInvocation("/review\tfoo")!!
        assertEquals("review", tabbed.name)
        assertEquals("foo", tabbed.arguments)

        val newlined = parseSlashCommandInvocation("/review\nfoo")!!
        assertEquals("review", newlined.name)
        assertEquals("foo", newlined.arguments)
    }

    @Test
    fun autocomplete_onlyWhenStartsWithSlashAndCursorInFirstToken() {
        assertNull(detectSlashCommandAutocomplete("hello", cursorPosition = 1))
        assertNull(detectSlashCommandAutocomplete(" hello", cursorPosition = 1))

        val onSlash = detectSlashCommandAutocomplete("/", cursorPosition = 1)
        assertNotNull(onSlash)
        assertEquals("", onSlash.query)

        val partial = detectSlashCommandAutocomplete("/rev", cursorPosition = 4)
        assertNotNull(partial)
        assertEquals("rev", partial.query)

        // Cursor after whitespace should not show the popup
        assertNull(detectSlashCommandAutocomplete("/review foo", cursorPosition = 8))
    }
}
