package com.ratulsarna.ocmobile.data.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class OpenCodeApiAuthHeadersTest {

    @Test
    fun createOpenCodeHttpClient_addsAuthorizationHeaderWhenTokenPresent() = runTest {
        var seenAuth: String? = null
        val engine = MockEngine { request ->
            seenAuth = request.headers[HttpHeaders.Authorization]
            respond("{}", status = HttpStatusCode.OK, headers = headersOf("Content-Type", "application/json"))
        }

        val client = createOpenCodeHttpClient(
            engine = engine,
            directoryProvider = null,
            authTokenProvider = { "tok" }
        )

        client.get("http://example.com/path")
        assertEquals("Bearer tok", seenAuth)
    }

    @Test
    fun createOpenCodeHttpClient_omitsAuthorizationHeaderWhenTokenBlank() = runTest {
        var seenAuth: String? = "unset"
        val engine = MockEngine { request ->
            seenAuth = request.headers[HttpHeaders.Authorization]
            respond("{}", status = HttpStatusCode.OK, headers = headersOf("Content-Type", "application/json"))
        }

        val client = createOpenCodeHttpClient(
            engine = engine,
            directoryProvider = null,
            authTokenProvider = { " " }
        )

        client.get("http://example.com/path")
        assertNull(seenAuth)
    }

    @Test
    fun createSseHttpClient_addsAuthorizationHeaderWhenTokenPresent() = runTest {
        var seenAuth: String? = null
        val engine = MockEngine { request ->
            seenAuth = request.headers[HttpHeaders.Authorization]
            respond("event: ping\ndata: {}\n\n", status = HttpStatusCode.OK, headers = headersOf("Content-Type", "text/event-stream"))
        }

        val client = createSseHttpClient(
            engine = engine,
            directoryProvider = null,
            authTokenProvider = { "tok" }
        )

        client.get("http://example.com/global/event")
        assertEquals("Bearer tok", seenAuth)
    }
}

