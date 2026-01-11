package com.ratulsarna.ocmobile.domain.model

/**
 * A stable identifier for an OpenCode "installation" on a machine (i.e. a specific storage root).
 *
 * We derive this from server-reported filesystem paths for OpenCode's state + config locations
 * returned by `GET /path`. This remains stable across server restarts and port changes on the same
 * machine, which lets the mobile app share workspace history across multiple server profiles that
 * point to the same installation.
 */
object InstallationId {

    fun from(statePath: String, configPath: String): String {
        val normalizedState = statePath.trim()
        val normalizedConfig = configPath.trim()
        val input = "$normalizedState|$normalizedConfig"
        val hash = fnv1a64(input.encodeToByteArray())
        return hash.toULong().toString(16).padStart(16, '0')
    }

    private fun fnv1a64(bytes: ByteArray): Long {
        var hash = 0xcbf29ce484222325UL // 14695981039346656037
        val prime = 0x100000001b3UL // 1099511628211
        for (b in bytes) {
            hash = hash xor (b.toUByte().toULong())
            hash *= prime
        }
        return hash.toLong()
    }
}

