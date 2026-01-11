package com.ratulsarna.ocmobile.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class InstallationIdTest {

    @Test
    fun InstallationId_isStableForSameStateAndConfig() {
        val a = InstallationId.from(
            statePath = "/Users/me/Library/Application Support/opencode/state",
            configPath = "/Users/me/Library/Application Support/opencode/config"
        )
        val b = InstallationId.from(
            statePath = "/Users/me/Library/Application Support/opencode/state",
            configPath = "/Users/me/Library/Application Support/opencode/config"
        )

        assertEquals(a, b)
        assertNotEquals(
            a,
            InstallationId.from(
                statePath = "/Users/me/Library/Application Support/opencode/state2",
                configPath = "/Users/me/Library/Application Support/opencode/config"
            )
        )
        assertNotEquals(
            a,
            InstallationId.from(
                statePath = "/Users/me/Library/Application Support/opencode/state",
                configPath = "/Users/me/Library/Application Support/opencode/config2"
            )
        )
    }
}

