package com.ratulsarna.ocmobile.data.dto

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectInfoDtoTest {

    @Test
    fun ProjectInfoDto_decodesWhenTimeUpdatedMissing() {
        val raw = """
            [
              {
                "id": "p1",
                "worktree": "/repo1",
                "name": "Repo 1",
                "time": { "created": 1, "updated": 2 }
              },
              {
                "id": "p2",
                "worktree": "/repo2",
                "name": "Repo 2",
                "time": { "created": 10 }
              }
            ]
        """.trimIndent()

        val json = Json { ignoreUnknownKeys = true }
        val decoded = json.decodeFromString<List<ProjectInfoDto>>(raw)

        assertEquals(2, decoded.size)
        assertEquals(2L, decoded[0].time?.updated)
        assertEquals(10L, decoded[1].time?.updated)
    }
}

