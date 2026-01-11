package com.ratulsarna.ocmobile

import com.ratulsarna.ocmobile.ui.components.markdown.linkifyUrls
import kotlin.test.Test
import kotlin.test.assertEquals

class UrlLinkifierTest {

    @Test
    fun `linkifies bare https URLs and trims trailing punctuation`() {
        val input = "See https://a.com)."
        val expected = "See [https://a.com](https://a.com))."
        assertEquals(expected, linkifyUrls(input))
    }

    @Test
    fun `does not linkify URLs inside inline code`() {
        val input = "Run `curl https://example.com` please."
        assertEquals(input, linkifyUrls(input))
    }

    @Test
    fun `does not linkify URLs inside fenced code blocks`() {
        val input = """
            Here:
            ```bash
            curl https://example.com
            ```
        """.trimIndent()
        assertEquals(input, linkifyUrls(input))
    }

    @Test
    fun `does not double wrap existing markdown links`() {
        val input = "Go to [Example](https://example.com)."
        assertEquals(input, linkifyUrls(input))
    }

    @Test
    fun `keeps balanced parentheses inside URL`() {
        val input = "Ref https://en.wikipedia.org/wiki/Function_(mathematics) now"
        val expected =
            "Ref [https://en.wikipedia.org/wiki/Function_(mathematics)](https://en.wikipedia.org/wiki/Function_(mathematics)) now"
        assertEquals(expected, linkifyUrls(input))
    }
}

