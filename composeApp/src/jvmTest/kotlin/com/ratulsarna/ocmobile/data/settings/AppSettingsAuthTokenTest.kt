package com.ratulsarna.ocmobile.data.settings

import com.ratulsarna.ocmobile.data.repository.ServerRepositoryImpl
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class AppSettingsAuthTokenTest {

    @Test
    fun AppSettings_authToken_isScopedByActiveServer() = runTest {
        val appSettings = AppSettingsImpl(settings = MapSettings())
        val serverRepo = ServerRepositoryImpl(appSettings = appSettings, defaultBaseUrl = { "http://example.com" })

        val a = serverRepo.addServer("A", "http://a.local:4096").getOrThrow()
        val b = serverRepo.addServer("B", "http://b.local:4096").getOrThrow()

        serverRepo.setActiveServer(a.id).getOrThrow()
        appSettings.setAuthTokenForServer(a.id, "token-a")
        assertEquals("token-a", appSettings.getAuthTokenForActiveServerSnapshot())

        serverRepo.setActiveServer(b.id).getOrThrow()
        assertNull(appSettings.getAuthTokenForActiveServerSnapshot())

        appSettings.setAuthTokenForServer(b.id, "token-b")
        assertEquals("token-b", appSettings.getAuthTokenForActiveServerSnapshot())

        serverRepo.setActiveServer(a.id).getOrThrow()
        assertEquals("token-a", appSettings.getAuthTokenForActiveServerSnapshot())
    }

    @Test
    fun AppSettings_authToken_blankClears() = runTest {
        val appSettings = AppSettingsImpl(settings = MapSettings())
        val serverRepo = ServerRepositoryImpl(appSettings = appSettings, defaultBaseUrl = { "http://example.com" })
        val a = serverRepo.addServer("A", "http://a.local:4096").getOrThrow()
        serverRepo.setActiveServer(a.id).getOrThrow()

        appSettings.setAuthTokenForServer(a.id, "token-a")
        assertEquals("token-a", appSettings.getAuthTokenForActiveServerSnapshot())

        appSettings.setAuthTokenForServer(a.id, " ")
        assertNull(appSettings.getAuthTokenForActiveServerSnapshot())
    }
}

