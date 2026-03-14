package com.ratulsarna.ocmobile.data.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class OpenCodeApiConfiguredProvidersTest {

    @Test
    fun OpenCodeApi_usesConfigProvidersEndpoint_whenAvailable() = runTest {
        val requestedPaths = mutableListOf<String>()
        val engine = MockEngine { request ->
            requestedPaths += request.url.encodedPath
            respond(
                content = ByteReadChannel(
                    """
                    {"providers":[{"id":"anthropic","name":"Anthropic","models":{"claude":{"id":"claude","name":"Claude"}}}],"default":{"anthropic":"claude"}}
                    """.trimIndent()
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val client = createOpenCodeHttpClient(engine = engine)
        val api = OpenCodeApiImpl(client, baseUrl = "http://example.com")

        val response = api.getConfiguredProviders()

        assertEquals(listOf("/config/providers"), requestedPaths)
        assertEquals(listOf("anthropic"), response.providers.map { it.id })
        assertEquals("claude", response.default["anthropic"])
    }

    @Test
    fun OpenCodeApi_fallsBackToLegacyProviderEndpoint_whenConfigProvidersMissing() = runTest {
        val requestedPaths = mutableListOf<String>()
        val engine = MockEngine { request ->
            requestedPaths += request.url.encodedPath
            when (request.url.encodedPath) {
                "/config/providers" -> respond(
                    content = ByteReadChannel("{}"),
                    status = HttpStatusCode.NotFound,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )

                "/provider" -> respond(
                    content = ByteReadChannel(
                        """
                        {"all":[{"id":"anthropic","name":"Anthropic","models":{"claude":{"id":"claude","name":"Claude"}}},{"id":"openai","name":"OpenAI","models":{"gpt-4o":{"id":"gpt-4o","name":"GPT-4o"}}}],"connected":["anthropic"],"default":{"anthropic":"claude","openai":"gpt-4o"}}
                        """.trimIndent()
                    ),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )

                else -> error("Unexpected path: ${request.url.encodedPath}")
            }
        }

        val client = createOpenCodeHttpClient(engine = engine)
        val api = OpenCodeApiImpl(client, baseUrl = "http://example.com")

        val response = api.getConfiguredProviders()

        assertEquals(listOf("/config/providers", "/provider"), requestedPaths)
        assertEquals(listOf("anthropic"), response.providers.map { it.id })
        assertEquals(mapOf("anthropic" to "claude"), response.default)
    }
}
