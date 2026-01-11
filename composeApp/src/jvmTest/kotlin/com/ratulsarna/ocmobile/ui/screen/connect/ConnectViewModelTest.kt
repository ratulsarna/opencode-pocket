package com.ratulsarna.ocmobile.ui.screen.connect

import com.ratulsarna.ocmobile.data.repository.ServerRepositoryImpl
import com.ratulsarna.ocmobile.data.settings.AppSettingsImpl
import com.ratulsarna.ocmobile.domain.model.SelectedModel
import com.ratulsarna.ocmobile.domain.model.Workspace
import com.ratulsarna.ocmobile.ui.theme.ThemeMode
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectViewModelTest {

    @Test
    fun ConnectViewModel_validatesBeforeSaving() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val settings = AppSettingsImpl(settings = MapSettings())
            val servers = ServerRepositoryImpl(appSettings = settings, defaultBaseUrl = { "http://example.com" })

            var validated = false
            val vm = ConnectViewModel(
                serverRepository = servers,
                appSettings = settings,
                validateConnection = { baseUrl, token ->
                    assertEquals("http://127.0.0.1:4096", baseUrl)
                    assertEquals("abc", token)
                    validated = true
                    Result.success(Unit)
                }
            )

            vm.setPairingString("oc-pocket-pair:v1:eyJ2ZXJzaW9uIjoxLCJiYXNlVXJsIjoiaHR0cDovLzEyNy4wLjAuMTo0MDk2IiwidG9rZW4iOiJhYmMiLCJuYW1lIjoiTXkgTWFjIn0")
            vm.connect()
            advanceUntilIdle()

            assertTrue(validated)
            val state = vm.uiState.value
            assertNotNull(state.connectedServerId)
            assertEquals("abc", settings.getAuthTokenForActiveServerSnapshot())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun ConnectViewModel_disconnectWipesConnectionStateButPreservesUiPrefs() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val settings = AppSettingsImpl(settings = MapSettings())
            val servers = ServerRepositoryImpl(appSettings = settings, defaultBaseUrl = { "http://example.com" })

            settings.setThemeMode(ThemeMode.DARK)

            val seeded = servers.addServer(name = "My Mac", baseUrlInput = "http://127.0.0.1:4096").getOrThrow()
            servers.setActiveServer(seeded.id).getOrThrow()
            settings.setAuthTokenForServer(seeded.id, "old-token")
            settings.setInstallationIdForServer(seeded.id, "inst-1")
            settings.setWorkspacesForInstallation(
                "inst-1",
                listOf(Workspace(projectId = "proj-1", worktree = "/tmp/proj-1", name = "Proj 1", lastUsedAtMs = 0))
            )
            settings.setActiveWorkspace("inst-1", Workspace(projectId = "proj-1", worktree = "/tmp/proj-1", name = "Proj 1", lastUsedAtMs = 0))
            settings.setCurrentSessionId("ses-1")
            settings.setSelectedAgent("agent-1")
            settings.setSelectedModel(SelectedModel(providerId = "anthropic", modelId = "claude"))
            settings.setFavoriteModels(listOf(SelectedModel(providerId = "openai", modelId = "gpt-4.1")))
            settings.setThinkingVariantForModel(providerId = "openai", modelId = "gpt-4.1", variant = "high")

            val vm = ConnectViewModel(serverRepository = servers, appSettings = settings, validateConnection = { _, _ -> Result.success(Unit) })

            vm.disconnect()
            advanceUntilIdle()

            assertEquals(ThemeMode.DARK, settings.getThemeMode().first())
            assertEquals(emptyList(), servers.getServersSnapshot())
            assertNull(servers.getActiveServerIdSnapshot())
            assertNull(settings.getAuthTokenForActiveServerSnapshot())
            assertNull(settings.getInstallationIdForActiveServerSnapshot())
            assertEquals(emptyList(), settings.getWorkspacesSnapshot())
            assertNull(settings.getActiveWorkspaceSnapshot())
            assertNull(settings.getCurrentSessionIdSnapshot())
            assertNull(settings.getSelectedAgent().first())
            assertNull(settings.getSelectedModelSnapshot())
            assertEquals(emptyList(), settings.getFavoriteModelsSnapshot())
            assertEquals(emptyMap(), settings.getThinkingVariantsByModel().first())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun ConnectViewModel_pairingWipesOldConnectionStateAndPreservesUiPrefs() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val settings = AppSettingsImpl(settings = MapSettings())
            val servers = ServerRepositoryImpl(appSettings = settings, defaultBaseUrl = { "http://example.com" })

            settings.setThemeMode(ThemeMode.DARK)

            val seeded = servers.addServer(name = "Old Mac", baseUrlInput = "http://127.0.0.1:4000").getOrThrow()
            servers.setActiveServer(seeded.id).getOrThrow()
            settings.setAuthTokenForServer(seeded.id, "old-token")
            settings.setSelectedAgent("agent-1")
            settings.setSelectedModel(SelectedModel(providerId = "anthropic", modelId = "claude"))

            val vm = ConnectViewModel(
                serverRepository = servers,
                appSettings = settings,
                validateConnection = { _, _ -> Result.success(Unit) }
            )

            vm.setPairingString("oc-pocket-pair:v1:eyJ2ZXJzaW9uIjoxLCJiYXNlVXJsIjoiaHR0cDovLzEyNy4wLjAuMTo0MDk2IiwidG9rZW4iOiJuZXctdG9rZW4iLCJuYW1lIjoiTXkgTmV3IE1hYyJ9")
            vm.connect()
            advanceUntilIdle()

            val nextServers = servers.getServersSnapshot()
            assertEquals(1, nextServers.size)
            assertEquals("My New Mac", nextServers.first().name)
            assertEquals("http://127.0.0.1:4096", nextServers.first().baseUrl)
            assertEquals(nextServers.first().id, servers.getActiveServerIdSnapshot())
            assertEquals("new-token", settings.getAuthTokenForActiveServerSnapshot())

            assertEquals(ThemeMode.DARK, settings.getThemeMode().first())
            assertNull(settings.getSelectedModelSnapshot())
        } finally {
            Dispatchers.resetMain()
        }
    }
}
