package com.ratulsarna.ocmobile.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MeetingLinkExtractorTest {

    @Test
    fun extractsSimpleMeetUrl() {
        val text = "Join https://meet.google.com/abc-defg-hij now"
        assertEquals("https://meet.google.com/abc-defg-hij", extractFirstGoogleMeetUrl(text))
    }

    @Test
    fun trimsTrailingPunctuation() {
        val text = "Join (https://meet.google.com/abc-defg-hij). Thanks!"
        assertEquals("https://meet.google.com/abc-defg-hij", extractFirstGoogleMeetUrl(text))
    }

    @Test
    fun extractsFromMarkdownLink() {
        val text = "Meeting: [join](https://meet.google.com/abc-defg-hij)"
        assertEquals("https://meet.google.com/abc-defg-hij", extractFirstGoogleMeetUrl(text))
    }

    @Test
    fun returnsFirstMeetUrlWhenMultiplePresent() {
        val text = "A https://meet.google.com/aaa-bbbb-ccc then https://meet.google.com/ddd-eeee-fff"
        assertEquals("https://meet.google.com/aaa-bbbb-ccc", extractFirstGoogleMeetUrl(text))
    }

    @Test
    fun ignoresNonMeetUrls() {
        val text = "See https://example.com/abc and https://google.com"
        assertNull(extractFirstGoogleMeetUrl(text))
    }

    @Test
    fun ignoresHttpMeetLinks() {
        val text = "Join http://meet.google.com/abc-defg-hij"
        assertNull(extractFirstGoogleMeetUrl(text))
    }

    @Test
    fun rejectsIncompleteMeetUrlPrefix() {
        assertNull(extractFirstGoogleMeetUrl("https://meet.google.com/"))
        assertNull(extractFirstGoogleMeetUrl("Join https://meet.google.com/ now"))
        assertNull(extractFirstGoogleMeetUrl("(https://meet.google.com/)"))
    }
}
