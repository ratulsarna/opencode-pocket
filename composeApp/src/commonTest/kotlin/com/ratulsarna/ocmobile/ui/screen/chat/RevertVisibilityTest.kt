package com.ratulsarna.ocmobile.ui.screen.chat

import com.ratulsarna.ocmobile.domain.model.AssistantMessage
import com.ratulsarna.ocmobile.domain.model.TextPart
import com.ratulsarna.ocmobile.domain.model.UserMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.datetime.Instant

class RevertVisibilityTest {
    private val t0 = Instant.fromEpochMilliseconds(0)

    @Test
    fun filterMessagesForRevert_noRevert_returnsAll() {
        val messages = listOf(
            UserMessage(id = "01A", sessionId = "s", createdAt = t0, parts = emptyList()),
            AssistantMessage(id = "01B", sessionId = "s", createdAt = t0, parts = emptyList())
        )

        assertEquals(messages, filterMessagesForRevert(messages, null))
        assertEquals(messages, filterMessagesForRevert(messages, ""))
    }

    @Test
    fun filterMessagesForRevert_hidesMessagesAfterRevertPoint() {
        val m1 = UserMessage(id = "01A", sessionId = "s", createdAt = t0, parts = emptyList())
        val m2 = AssistantMessage(id = "01B", sessionId = "s", createdAt = t0, parts = emptyList())
        val m3 = UserMessage(id = "01C", sessionId = "s", createdAt = t0, parts = emptyList())

        assertEquals(listOf(m1), filterMessagesForRevert(listOf(m1, m2, m3), "01B"))
    }

    @Test
    fun filterMessagesForRevert_keepsOptimisticPendingMessages() {
        val m1 = UserMessage(id = "01A", sessionId = "s", createdAt = t0, parts = emptyList())
        val pending = UserMessage(id = "pending-1", sessionId = "s", createdAt = t0, parts = emptyList())

        assertEquals(listOf(pending), filterMessagesForRevert(listOf(m1, pending), "01A"))
    }

    @Test
    fun promptTextForRevert_returnsLastUserTextBeforeOrAtRevert_excludingSynthetic() {
        val user1 = UserMessage(
            id = "01A",
            sessionId = "s",
            createdAt = t0,
            parts = listOf(
                TextPart(id = "t1", text = "hello", synthetic = false),
                TextPart(id = "t2", text = "HOOK", synthetic = true)
            )
        )
        val assistant = AssistantMessage(id = "01B", sessionId = "s", createdAt = t0, parts = emptyList())
        val user2 = UserMessage(
            id = "01C",
            sessionId = "s",
            createdAt = t0,
            parts = listOf(TextPart(id = "t3", text = "next", synthetic = false))
        )

        assertEquals("hello", promptTextForRevert(listOf(user1, assistant, user2), "01B"))
        assertEquals("next", promptTextForRevert(listOf(user1, assistant, user2), "01C"))
        assertNull(promptTextForRevert(listOf(assistant), "01B"))
    }
}
