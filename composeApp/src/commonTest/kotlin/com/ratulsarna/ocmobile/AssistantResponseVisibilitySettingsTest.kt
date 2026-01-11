package com.ratulsarna.ocmobile

import com.ratulsarna.ocmobile.domain.model.AssistantResponsePartVisibility
import com.ratulsarna.ocmobile.domain.model.AssistantResponseVisibilityPreset
import com.ratulsarna.ocmobile.domain.model.AssistantResponseVisibilitySettings
import kotlin.test.Test
import kotlin.test.assertEquals

class AssistantResponseVisibilitySettingsTest {

    @Test
    fun `effective returns all when CUSTOM preset has null custom`() {
        val settings = AssistantResponseVisibilitySettings(
            preset = AssistantResponseVisibilityPreset.CUSTOM,
            custom = null
        )

        assertEquals(AssistantResponsePartVisibility.all(), settings.effective())
    }
}
