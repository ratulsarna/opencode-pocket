package com.ratulsarna.ocmobile.ui.screen.sessions

import com.ratulsarna.ocmobile.data.mock.MockAppSettings
import com.ratulsarna.ocmobile.domain.model.Session
import com.ratulsarna.ocmobile.domain.repository.SessionRepository
import com.ratulsarna.ocmobile.testing.MainDispatcherRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Rule

@OptIn(ExperimentalCoroutinesApi::class)
class SessionsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(dispatcher)

    @Test
    fun SessionsViewModel_filtersRootSessionsAndSortsByUpdatedDesc() = runTest(dispatcher) {
        val appSettings = MockAppSettings()

        val sessions = listOf(
            session(id = "ses-root-old", parentId = null, updatedAtMs = 100),
            session(id = "ses-child-new", parentId = "ses-root-old", updatedAtMs = 400),
            session(id = "ses-root-new", parentId = null, updatedAtMs = 300)
        )

        val repo = FakeSessionRepository(
            getSessionsHandler = { _, _, _ -> Result.success(sessions) }
        )

        val vm = SessionsViewModel(
            sessionRepository = repo,
            appSettings = appSettings,
            debounceMs = 150L,
            searchLimit = 30
        )

        advanceUntilIdle()

        val visible = vm.uiState.value.sessions
        assertEquals(listOf("ses-root-new", "ses-root-old"), visible.map { it.id })
        assertTrue(visible.all { it.parentId == null })
    }

    @Test
    fun SessionsViewModel_searchDebouncesAndUsesServerSearchLimit() = runTest(dispatcher) {
        val appSettings = MockAppSettings()
        val calls = mutableListOf<Triple<String?, Int?, Long?>>()

        val repo = FakeSessionRepository(
            getSessionsHandler = { search, limit, start ->
                calls.add(Triple(search, limit, start))
                Result.success(emptyList())
            }
        )

        val vm = SessionsViewModel(
            sessionRepository = repo,
            appSettings = appSettings,
            debounceMs = 150L,
            searchLimit = 30
        )
        advanceUntilIdle()
        calls.clear() // Ignore init load for this test

        vm.onSearchQueryChanged("foo")
        advanceTimeBy(149)
        assertEquals(0, calls.size)

        advanceTimeBy(1)
        advanceUntilIdle()

        assertEquals(1, calls.size)
        assertEquals("foo", calls.single().first)
        assertEquals(30, calls.single().second)

        vm.onSearchQueryChanged("")
        advanceTimeBy(150)
        advanceUntilIdle()

        assertEquals(2, calls.size)
        assertEquals(null, calls.last().first)
    }

    @Test
    fun SessionsViewModel_activateSessionUpdatesActiveSessionIdAndEmitsSuccess() = runTest(dispatcher) {
        val appSettings = MockAppSettings()

        val repo = FakeSessionRepository(
            getSessionsHandler = { _, _, _ -> Result.success(emptyList()) },
            updateCurrentSessionIdHandler = { Result.success(Unit) }
        )

        val vm = SessionsViewModel(
            sessionRepository = repo,
            appSettings = appSettings,
            debounceMs = 150L,
            searchLimit = 30
        )
        advanceUntilIdle()

        vm.activateSession("ses-123")
        assertEquals("ses-123", vm.uiState.value.activatingSessionId)

        advanceUntilIdle()

        assertEquals("ses-123", vm.uiState.value.activatedSessionId)
        assertEquals(null, vm.uiState.value.activatingSessionId)
    }

    @Test
    fun SessionsViewModel_createNewSessionPersistsAndEmitsNewSessionId() = runTest(dispatcher) {
        val appSettings = MockAppSettings()

        val repo = FakeSessionRepository(
            getSessionsHandler = { _, _, _ -> Result.success(emptyList()) },
            createSessionHandler = { Result.success(session(id = "ses-new", parentId = null, updatedAtMs = 1)) }
        )

        val vm = SessionsViewModel(
            sessionRepository = repo,
            appSettings = appSettings,
            debounceMs = 150L,
            searchLimit = 30
        )
        advanceUntilIdle()

        vm.createNewSession()
        advanceUntilIdle()

        assertEquals("ses-new", vm.uiState.value.newSessionId)
    }

    @Test
    fun SessionsViewModel_preservesSessionsOnLoadFailure() = runTest(dispatcher) {
        val appSettings = MockAppSettings()

        val sessions = listOf(
            session(id = "ses-a", parentId = null, updatedAtMs = 100),
            session(id = "ses-b", parentId = null, updatedAtMs = 200)
        )

        var callCount = 0
        val repo = FakeSessionRepository(
            getSessionsHandler = { _, _, _ ->
                callCount += 1
                if (callCount == 1) {
                    Result.success(sessions)
                } else {
                    Result.failure(RuntimeException("boom"))
                }
            }
        )

        val vm = SessionsViewModel(
            sessionRepository = repo,
            appSettings = appSettings,
            debounceMs = 150L,
            searchLimit = 30
        )
        advanceUntilIdle()

        assertEquals(2, vm.uiState.value.sessions.size)

        vm.refresh()
        advanceUntilIdle()

        assertEquals(2, vm.uiState.value.sessions.size)
        assertEquals("boom", vm.uiState.value.error)
    }

    @Test
    fun SessionsViewModel_searchRetriesWithLargerLimitWhenOnlyChildSessionsReturned() = runTest(dispatcher) {
        val appSettings = MockAppSettings()
        val calls = mutableListOf<Triple<String?, Int?, Long?>>()

        val children = (1..30).map { idx ->
            session(id = "ses-child-$idx", parentId = "ses-root", updatedAtMs = idx.toLong())
        }
        val roots = listOf(session(id = "ses-root-match", parentId = null, updatedAtMs = 999))

        val repo = FakeSessionRepository(
            getSessionsHandler = { search, limit, start ->
                calls.add(Triple(search, limit, start))
                if (search.isNullOrBlank()) return@FakeSessionRepository Result.success(emptyList())
                if (limit == 30) return@FakeSessionRepository Result.success(children)
                if (limit == 200) return@FakeSessionRepository Result.success(children + roots)
                Result.success(emptyList())
            }
        )

        val vm = SessionsViewModel(
            sessionRepository = repo,
            appSettings = appSettings,
            debounceMs = 150L,
            searchLimit = 30
        )
        advanceUntilIdle()
        calls.clear()

        vm.onSearchQueryChanged("match")
        advanceTimeBy(150)
        advanceUntilIdle()

        assertEquals(listOf(30, 200), calls.map { it.second })
        assertEquals(listOf("ses-root-match"), vm.uiState.value.sessions.map { it.id })
    }

    @Test
    fun SessionsViewModel_searchExpandsWhenRootsAreTruncatedByChildSessions() = runTest(dispatcher) {
        val appSettings = MockAppSettings()
        val calls = mutableListOf<Triple<String?, Int?, Long?>>()

        val rootsFirstPage = (1..5).map { idx ->
            session(id = "ses-root-$idx", parentId = null, updatedAtMs = (500 + idx).toLong())
        }
        val children = (1..25).map { idx ->
            session(id = "ses-child-$idx", parentId = "ses-root-1", updatedAtMs = idx.toLong())
        }
        val extraRoots = (6..12).map { idx ->
            session(id = "ses-root-$idx", parentId = null, updatedAtMs = (600 + idx).toLong())
        }

        val repo = FakeSessionRepository(
            getSessionsHandler = { search, limit, start ->
                calls.add(Triple(search, limit, start))
                if (search.isNullOrBlank()) return@FakeSessionRepository Result.success(emptyList())
                if (limit == 30) return@FakeSessionRepository Result.success(children + rootsFirstPage)
                if (limit == 200) return@FakeSessionRepository Result.success(children + rootsFirstPage + extraRoots)
                Result.success(emptyList())
            }
        )

        val vm = SessionsViewModel(
            sessionRepository = repo,
            appSettings = appSettings,
            debounceMs = 150L,
            searchLimit = 30
        )
        advanceUntilIdle()
        calls.clear()

        vm.onSearchQueryChanged("root")
        advanceTimeBy(150)
        advanceUntilIdle()

        assertEquals(listOf(30, 200), calls.map { it.second })
        val ids = vm.uiState.value.sessions.map { it.id }
        // We should see more than the 5 root sessions available in the first page after expansion.
        assertTrue(ids.size > 5)
        assertTrue(ids.all { it.startsWith("ses-root-") })
    }

    private fun session(id: String, parentId: String?, updatedAtMs: Long): Session {
        val instant = Instant.fromEpochMilliseconds(updatedAtMs)
        return Session(
            id = id,
            directory = "/mock",
            title = id,
            createdAt = instant,
            updatedAt = instant,
            parentId = parentId
        )
    }

    private class FakeSessionRepository(
        private val getSessionsHandler: suspend (search: String?, limit: Int?, start: Long?) -> Result<List<Session>>,
        private val createSessionHandler: suspend () -> Result<Session> = { error("createSession not configured") },
        private val updateCurrentSessionIdHandler: suspend (String) -> Result<Unit> = { error("updateCurrentSessionId not configured") }
    ) : SessionRepository {
        override suspend fun getCurrentSessionId(): Result<String> = error("not used")
        override suspend fun getSession(sessionId: String): Result<Session> = error("not used")

        override suspend fun getSessions(search: String?, limit: Int?, start: Long?): Result<List<Session>> =
            getSessionsHandler(search, limit, start)

        override suspend fun createSession(title: String?, parentId: String?): Result<Session> {
            return createSessionHandler()
        }

        override suspend fun forkSession(sessionId: String, messageId: String?): Result<Session> = error("not used")
        override suspend fun revertSession(sessionId: String, messageId: String): Result<Session> = error("not used")
        override suspend fun updateCurrentSessionId(sessionId: String): Result<Unit> = updateCurrentSessionIdHandler(sessionId)
        override suspend fun abortSession(sessionId: String): Result<Boolean> = error("not used")
    }
}
