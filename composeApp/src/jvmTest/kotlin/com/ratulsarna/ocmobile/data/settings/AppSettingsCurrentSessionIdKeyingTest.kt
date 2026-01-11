package com.ratulsarna.ocmobile.data.settings

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class AppSettingsCurrentSessionIdKeyingTest {

    @Test
    fun AppSettings_currentSessionId_isIsolatedByInstallation_whenNoWorkspaceIsActive() = runTest {
        val appSettings = AppSettingsImpl(settings = MapSettings())

        appSettings.setActiveServerId("srv1")
        appSettings.setInstallationIdForServer("srv1", "inst1")
        appSettings.setCurrentSessionId("sess1")

        appSettings.setActiveServerId("srv2")
        appSettings.setInstallationIdForServer("srv2", "inst2")
        assertEquals(null, appSettings.getCurrentSessionIdSnapshot())

        appSettings.setCurrentSessionId("sess2")

        appSettings.setActiveServerId("srv1")
        assertEquals("sess1", appSettings.getCurrentSessionIdSnapshot())
    }
}

