package com.ratulsarna.ocmobile.domain.repository

import com.ratulsarna.ocmobile.data.settings.AppSettingsImpl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import com.russhwolf.settings.MapSettings
import com.ratulsarna.ocmobile.domain.model.Workspace

@OptIn(ExperimentalCoroutinesApi::class)
class ServerRepositoryTest {

    @Test
    fun ServerRepository_seedsDefaultProfileWhenEmpty() = runTest {
        val settings = AppSettingsImpl(settings = MapSettings())
        val repo = com.ratulsarna.ocmobile.data.repository.ServerRepositoryImpl(
            appSettings = settings,
            defaultBaseUrl = { "http://example.com:3001" }
        )

        val active = repo.ensureInitialized().getOrThrow()
        assertNotNull(active.id)
        assertEquals("http://example.com:3001", active.baseUrl)
        assertEquals(active.id, repo.getActiveServerSnapshot()?.id)
    }

    @Test
    fun AppSettings_currentSessionId_isScopedByInstallationAndProject() = runTest {
        val appSettings = AppSettingsImpl(settings = MapSettings())
        val repo = com.ratulsarna.ocmobile.data.repository.ServerRepositoryImpl(
            appSettings = appSettings,
            defaultBaseUrl = { "http://example.com:3001" }
        )

        val a = repo.addServer(name = "A", baseUrlInput = "http://a.local:3001").getOrThrow()
        val b = repo.addServer(name = "B", baseUrlInput = "http://b.local:3001").getOrThrow()

        // Both servers point to the same OpenCode installation (same machine + storage).
        appSettings.setInstallationIdForServer(a.id, "inst1")
        appSettings.setInstallationIdForServer(b.id, "inst1")

        val projA = Workspace(projectId = "projA", worktree = "/repoA", name = "Repo A", lastUsedAtMs = null)
        val projB = Workspace(projectId = "projB", worktree = "/repoB", name = "Repo B", lastUsedAtMs = null)

        repo.setActiveServer(a.id).getOrThrow()
        appSettings.setActiveWorkspace("inst1", projA)
        appSettings.setCurrentSessionId("ses-a1")
        assertEquals("ses-a1", appSettings.getCurrentSessionIdSnapshot())

        appSettings.setActiveWorkspace("inst1", projB)
        appSettings.setCurrentSessionId("ses-b1")
        assertEquals("ses-b1", appSettings.getCurrentSessionIdSnapshot())

        appSettings.setActiveWorkspace("inst1", projA)
        assertEquals("ses-a1", appSettings.getCurrentSessionIdSnapshot())

        repo.setActiveServer(b.id).getOrThrow()
        // Switching servers with the same installation should keep the per-project session id.
        assertEquals("ses-a1", appSettings.getCurrentSessionIdSnapshot())
    }
}
