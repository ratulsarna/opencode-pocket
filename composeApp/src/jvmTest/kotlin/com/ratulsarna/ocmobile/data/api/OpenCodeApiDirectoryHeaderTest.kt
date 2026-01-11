package com.ratulsarna.ocmobile.data.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class OpenCodeApiDirectoryHeaderTest {

    @Test
    fun OpenCodeApi_addsXOpenCodeDirectoryHeader_whenDirectoryProviderReturnsValue() = runTest {
        var captured: String? = null

        val engine = MockEngine { request ->
            captured = request.headers["x-opencode-directory"]
            respond(
                content = ByteReadChannel("[]"),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val client = createOpenCodeHttpClient(
            engine = engine,
            directoryProvider = { "/repo" }
        )
        val api = OpenCodeApiImpl(client, baseUrl = "http://example.com")

        api.getSessions(search = null, limit = null, start = null)

        assertEquals("/repo", captured)
    }

    @Test
    fun OpenCodeApi_doesNotSendDuplicateDirectoryHeaders_whenRequestOverridesDirectory() = runTest {
        var captured: List<String>? = null

        val engine = MockEngine { request ->
            captured = request.headers.getAll("x-opencode-directory")
            respond(
                content = ByteReadChannel("""{"id":"p","worktree":"/repo","directory":"/repo","name":"Repo"}"""),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val client = createOpenCodeHttpClient(
            engine = engine,
            directoryProvider = { "/repo" }
        )
        val api = OpenCodeApiImpl(client, baseUrl = "http://example.com")

        api.getCurrentProject(directory = "/some/other/path")

        val values = assertNotNull(captured)
        assertEquals(listOf("/some/other/path"), values)
    }
}
