package com.ratulsarna.ocmobile.ui.components.markdown

import com.ratulsarna.ocmobile.util.extractFirstGoogleMeetUrl

/**
 * SwiftUI interop helper for preparing text for markdown rendering.
 *
 * SwiftUI screens (ChatUIKit, markdown viewer) rely on this for:
 * - converting bare URLs into Markdown links
 * - converting recognizable file paths into tappable links
 */
object MarkdownInterop {

    fun linkify(text: String): String = linkifyUrls(linkifyFilePaths(text))

    fun firstGoogleMeetUrl(text: String): String? = extractFirstGoogleMeetUrl(text)
}

