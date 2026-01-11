package com.ratulsarna.ocmobile

import com.ratulsarna.ocmobile.ui.components.markdown.FILE_LINK_PREFIX
import com.ratulsarna.ocmobile.ui.components.markdown.encodeFilePath
import com.ratulsarna.ocmobile.ui.components.markdown.linkifyFilePaths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FilePathLinkifierTest {

    private fun expectedLink(path: String): String = "[$path](${FILE_LINK_PREFIX}${encodeFilePath(path)})"

    @Test
    fun linkifiesPathsWithSpacesInsideSentence() {
        val path = "Notes/Dated/2025-12-19/Socket Implementation Review \u2013 December 19, 2025.md"
        val input = "See $path for details."

        val result = linkifyFilePaths(input)
        val expected = expectedLink(path)

        assertTrue(result.contains(expected))
    }

    @Test
    fun linkifiesAbsolutePaths() {
        val path = "/Users/example/Project/README.md"
        val input = "Open $path"

        val result = linkifyFilePaths(input)
        val expected = expectedLink(path)

        assertTrue(result.contains(expected))
    }

    @Test
    fun skipsInlineCodeWhenItIsNotJustAPath() {
        val input = "Use `cat Notes/Dated/file.md` in the script."

        val result = linkifyFilePaths(input)

        assertFalse(result.contains(FILE_LINK_PREFIX))
    }

    @Test
    fun linkifiesInlineCodeWhenItIsJustAPath() {
        val path = "Notes/Dated/2025-12-19/Socket Implementation Review \u2013 December 19, 2025.md"
        val input = "See `$path` for details."

        val result = linkifyFilePaths(input)
        val expected = expectedLink(path)

        assertTrue(result.contains(expected))
    }

    @Test
    fun skipsFencedCodeBlocks() {
        val path = "Notes/Dated/file.md"
        val input = "```\n$path\n```\nOutside $path"

        val result = linkifyFilePaths(input)
        val expected = expectedLink(path)

        val lines = result.split('\n')
        assertEquals(path, lines[1])
        assertTrue(result.contains(expected))
    }

    @Test
    fun ignoresDirectoryLikePaths() {
        val input = "Check notes/2025/ for more context."

        val result = linkifyFilePaths(input)

        assertFalse(result.contains(FILE_LINK_PREFIX))
    }

    @Test
    fun doesNotLinkifyUrlsInText() {
        val input = "See https://example.com/docs/file.md for more."

        val result = linkifyFilePaths(input)

        assertFalse(result.contains(FILE_LINK_PREFIX))
    }

    @Test
    fun preservesExistingMarkdownLinksToFilePaths() {
        val input = "See [doc](/foo/bar.md) for details."

        val result = linkifyFilePaths(input)

        assertEquals(input, result)
    }

    @Test
    fun preservesTrailingNewlines() {
        val path = "Notes/Dated/file.md"
        val input = "Outside $path\n\n"

        val result = linkifyFilePaths(input)

        assertTrue(result.endsWith("\n\n"))
    }

    @Test
    fun doesNotTreatInlineTripleBackticksAsFence() {
        val path = "/tmp/file.md"
        val input = "```foo``` Next $path"

        val result = linkifyFilePaths(input)
        val expected = expectedLink(path)

        assertTrue(result.contains(expected))
    }

    @Test
    fun doesNotCloseFenceWhenMarkerHasExtraText() {
        val path = "/tmp/file.md"
        val input = "```\ncode\n```not\nOutside $path"

        val result = linkifyFilePaths(input)

        assertFalse(result.contains(FILE_LINK_PREFIX))
    }

    @Test
    fun skipsFencedCodeBlocksInsideBlockQuotes() {
        val path = "/tmp/file.md"
        val input = "> ```\n> $path\n> ```\nOutside $path"

        val result = linkifyFilePaths(input)
        val expectedOutside = expectedLink(path)

        assertTrue(result.contains(expectedOutside))
        assertTrue(result.contains("> $path"))
        assertFalse(result.contains("> [$path]("))
    }

    @Test
    fun skipsFencedCodeBlocksInsideNestedBlockQuotes() {
        val path = "/tmp/file.md"
        val input = ">> ```\n>> $path\n>> ```\nOutside $path"

        val result = linkifyFilePaths(input)
        val expectedOutside = expectedLink(path)

        assertTrue(result.contains(expectedOutside))
        assertTrue(result.contains(">> $path"))
        assertFalse(result.contains(">> [$path]("))
    }

    @Test
    fun skipsFencedCodeBlocksInsideBlockQuotesWithIndentation() {
        val path = "/tmp/file.md"
        val input = ">   ```\n>   $path\n>   ```\nOutside $path"

        val result = linkifyFilePaths(input)
        val expectedOutside = expectedLink(path)

        assertTrue(result.contains(expectedOutside))
        assertTrue(result.contains(">   $path"))
        assertFalse(result.contains(">   [$path]("))
    }

    @Test
    fun skipsTildeFencedCodeBlocks() {
        val path = "/tmp/file.md"
        val input = "~~~\n$path\n~~~\nOutside $path"

        val result = linkifyFilePaths(input)
        val expectedOutside = expectedLink(path)

        val lines = result.split('\n')
        assertEquals(path, lines[1])
        assertTrue(result.contains(expectedOutside))
    }

    @Test
    fun preservesReferenceLinkDefinitions() {
        val path = "/tmp/file.md"
        val input = "[ref]: $path\nSee [doc][ref]"

        val result = linkifyFilePaths(input)

        assertTrue(result.contains("[ref]: $path"))
        assertFalse(result.contains("[ref]: [$path]("))
    }

    @Test
    fun preservesReferenceLinkDefinitionsInBlockQuotes() {
        val path = "/tmp/file.md"
        val input = "> [ref]: $path\nSee [doc][ref]"

        val result = linkifyFilePaths(input)

        assertTrue(result.contains("> [ref]: $path"))
        assertFalse(result.contains("> [ref]: [$path]("))
    }

    @Test
    fun preservesImageLinksToFilePaths() {
        val input = "![img](/foo/bar.md)"

        val result = linkifyFilePaths(input)

        assertEquals(input, result)
    }

    @Test
    fun trimsTrailingPunctuationFromLinkedPaths() {
        val path = "/tmp/file.md"
        val input = "Open $path)."

        val result = linkifyFilePaths(input)
        val expected = expectedLink(path)

        assertTrue(result.contains("$expected)."))
        assertFalse(result.contains("[$path)]("))
    }
}
