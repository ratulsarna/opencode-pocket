package com.ratulsarna.ocmobile

import com.ratulsarna.ocmobile.domain.model.AgentPart
import com.ratulsarna.ocmobile.domain.model.AssistantResponsePartVisibility
import com.ratulsarna.ocmobile.domain.model.CompactionPart
import com.ratulsarna.ocmobile.domain.model.FilePart
import com.ratulsarna.ocmobile.domain.model.PatchPart
import com.ratulsarna.ocmobile.domain.model.ReasoningPart
import com.ratulsarna.ocmobile.domain.model.RetryPart
import com.ratulsarna.ocmobile.domain.model.SnapshotPart
import com.ratulsarna.ocmobile.domain.model.StepFinishPart
import com.ratulsarna.ocmobile.domain.model.StepStartPart
import com.ratulsarna.ocmobile.domain.model.TextPart
import com.ratulsarna.ocmobile.domain.model.ToolPart
import com.ratulsarna.ocmobile.domain.model.ToolState
import com.ratulsarna.ocmobile.domain.model.UnknownPart
import com.ratulsarna.ocmobile.domain.model.filterVisibleParts
import kotlin.test.Test
import kotlin.test.assertEquals

class MessagePartVisibilityTest {

    @Test
    fun `text is always visible including synthetic`() {
        val parts = listOf(
            TextPart(text = "A"),
            TextPart(text = "hook", synthetic = true),
            ToolPart(callId = "c1", tool = "t", state = ToolState.COMPLETED)
        )

        val visible = filterVisibleParts(
            parts = parts,
            visibility = AssistantResponsePartVisibility.textOnly()
        )
        assertEquals(listOf("A", "hook"), visible.filterIsInstance<TextPart>().map { it.text })
    }

    @Test
    fun `file parts are always visible`() {
        val file = FilePart(mime = "text/plain", filename = "a.txt", url = "https://example.com/a.txt")
        val parts = listOf(
            TextPart(text = "A"),
            file,
            ReasoningPart(text = "thinking"),
            ToolPart(callId = "c1", tool = "t", state = ToolState.COMPLETED)
        )

        val visible = filterVisibleParts(
            parts = parts,
            visibility = AssistantResponsePartVisibility.textOnly()
        )

        assertEquals(true, visible.contains(file))
        assertEquals(2, visible.size) // TextPart + FilePart
    }

    @Test
    fun `text and thinking shows reasoning but hides tools and other internals`() {
        val parts = listOf(
            TextPart(text = "A"),
            ReasoningPart(text = "thinking"),
            ToolPart(callId = "c1", tool = "t", state = ToolState.COMPLETED),
            PatchPart(hash = "h", files = listOf("f")),
            AgentPart(name = "agent"),
            CompactionPart(auto = true),
            UnknownPart(type = "x", rawData = "{}")
        )

        val visible = filterVisibleParts(
            parts = parts,
            visibility = AssistantResponsePartVisibility.textAndThinking()
        )

        assertEquals(listOf(TextPart::class, ReasoningPart::class), visible.map { it::class })
    }

    @Test
    fun `step and snapshot parts are always excluded even in all mode`() {
        val parts = listOf(
            TextPart(text = "A"),
            StepStartPart(snapshot = "s1"),
            StepFinishPart(reason = "ok", snapshot = "s2"),
            SnapshotPart(snapshot = "snap")
        )

        val visible = filterVisibleParts(
            parts = parts,
            visibility = AssistantResponsePartVisibility.all()
        )

        assertEquals(listOf(TextPart::class), visible.map { it::class })
    }

    @Test
    fun `retries respect showRetries visibility`() {
        val retry = RetryPart(attempt = 1, error = null)
        val parts = listOf(
            TextPart(text = "A"),
            retry
        )

        val visibleHidden = filterVisibleParts(
            parts = parts,
            visibility = AssistantResponsePartVisibility.textOnly()
        )
        assertEquals(listOf(TextPart::class), visibleHidden.map { it::class })

        val visibleShown = filterVisibleParts(
            parts = parts,
            visibility = AssistantResponsePartVisibility.textOnly().copy(showRetries = true)
        )
        assertEquals(listOf(TextPart::class, RetryPart::class), visibleShown.map { it::class })
        assertEquals(true, visibleShown.contains(retry))
    }
}
