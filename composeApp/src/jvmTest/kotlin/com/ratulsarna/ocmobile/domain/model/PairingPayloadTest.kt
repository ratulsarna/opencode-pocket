package com.ratulsarna.ocmobile.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
class PairingPayloadTest {

    @Test
    fun PairingPayload_decode_validString() {
        val json = """{"version":1,"baseUrl":"http://127.0.0.1:4096","token":"abc","name":"My Mac"}"""
        val payload = Base64.encode(json.encodeToByteArray())
            .trimEnd('=')
            .replace('+', '-')
            .replace('/', '_')

        val raw = "oc-pocket-pair:v1:$payload"

        val decoded = PairingPayload.decode(raw).getOrThrow()
        assertEquals(1, decoded.version)
        assertEquals("http://127.0.0.1:4096", decoded.baseUrl)
        assertEquals("abc", decoded.token)
        assertEquals("My Mac", decoded.name)
    }

    @Test
    fun PairingPayload_decode_trimsWhitespace() {
        val json = """{"version":1,"baseUrl":"http://127.0.0.1:4096","token":"abc","name":null}"""
        val payload = Base64.encode(json.encodeToByteArray())
            .trimEnd('=')
            .replace('+', '-')
            .replace('/', '_')

        val raw = "  oc-pocket-pair:v1:$payload  "
        val decoded = PairingPayload.decode(raw).getOrThrow()
        assertEquals("http://127.0.0.1:4096", decoded.baseUrl)
    }

    @Test
    fun PairingPayload_decode_rejectsInvalidPrefix() {
        val error = PairingPayload.decode("not-a-pairing-string").exceptionOrNull()
        assertNotNull(error)
    }

    @Test
    fun PairingPayload_decode_rejectsMissingFields() {
        val json = """{"version":1,"baseUrl":"","token":"","name":"x"}"""
        val payload = Base64.encode(json.encodeToByteArray())
            .trimEnd('=')
            .replace('+', '-')
            .replace('/', '_')

        val error = PairingPayload.decode("oc-pocket-pair:v1:$payload").exceptionOrNull()
        assertNotNull(error)
    }

    @Test
    fun PairingPayload_decode_rejectsUnknownVersion() {
        val json = """{"version":2,"baseUrl":"http://127.0.0.1:4096","token":"abc","name":"x"}"""
        val payload = Base64.encode(json.encodeToByteArray())
            .trimEnd('=')
            .replace('+', '-')
            .replace('/', '_')

        val error = PairingPayload.decode("oc-pocket-pair:v1:$payload").exceptionOrNull()
        assertNotNull(error)
        assertTrue(error.message?.contains("version", ignoreCase = true) == true)
    }
}
