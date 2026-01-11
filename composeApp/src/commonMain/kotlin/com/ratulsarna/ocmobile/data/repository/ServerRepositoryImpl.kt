package com.ratulsarna.ocmobile.data.repository

import com.ratulsarna.ocmobile.data.settings.AppSettings
import com.ratulsarna.ocmobile.domain.model.ServerProfile
import com.ratulsarna.ocmobile.domain.repository.ServerRepository
import kotlin.time.Clock
import kotlin.random.Random
import kotlinx.coroutines.flow.Flow

class ServerRepositoryImpl(
    private val appSettings: AppSettings,
    private val defaultBaseUrl: () -> String
) : ServerRepository {

    override fun getServers(): Flow<List<ServerProfile>> = appSettings.getServerProfiles()

    override fun getServersSnapshot(): List<ServerProfile> = appSettings.getServerProfilesSnapshot()

    override fun getActiveServerId(): Flow<String?> = appSettings.getActiveServerId()

    override fun getActiveServerIdSnapshot(): String? = appSettings.getActiveServerIdSnapshot()

    override fun getActiveServerSnapshot(): ServerProfile? {
        val id = getActiveServerIdSnapshot() ?: return null
        return getServersSnapshot().firstOrNull { it.id == id }
    }

    override suspend fun ensureInitialized(): Result<ServerProfile> {
        return runCatching {
            val existing = getServersSnapshot()
            if (existing.isNotEmpty()) {
                val active = getActiveServerSnapshot()
                if (active != null) return@runCatching active

                val fallback = existing.first()
                setActiveServer(fallback.id).getOrThrow()
                return@runCatching fallback
            }

            val now = Clock.System.now().toEpochMilliseconds()
            val seeded = ServerProfile(
                id = generateId(now),
                name = "Default",
                baseUrl = normalizeBaseUrl(defaultBaseUrl()),
                createdAtMs = now,
                lastUsedAtMs = now
            )

            appSettings.setServerProfiles(listOf(seeded))
            appSettings.setActiveServerId(seeded.id)
            seeded
        }
    }

    override suspend fun addServer(name: String, baseUrlInput: String): Result<ServerProfile> {
        return runCatching {
            val now = Clock.System.now().toEpochMilliseconds()
            val profile = ServerProfile(
                id = generateId(now),
                name = name.trim().ifBlank { "Server" },
                baseUrl = normalizeBaseUrl(baseUrlInput),
                createdAtMs = now
            )

            val next = getServersSnapshot() + profile
            appSettings.setServerProfiles(next)

            if (appSettings.getActiveServerIdSnapshot().isNullOrBlank()) {
                appSettings.setActiveServerId(profile.id)
            }

            profile
        }
    }

    override suspend fun setActiveServer(serverId: String): Result<Unit> {
        return runCatching {
            val id = serverId.trim()
            val existing = getServersSnapshot()
            val match = existing.firstOrNull { it.id == id } ?: error("Server not found: $id")

            val now = Clock.System.now().toEpochMilliseconds()
            val updated = existing.map { profile ->
                if (profile.id == match.id) profile.copy(lastUsedAtMs = now) else profile
            }
            appSettings.setServerProfiles(updated)
            appSettings.setActiveServerId(id)
        }
    }

    private fun normalizeBaseUrl(raw: String): String {
        val trimmed = raw.trim().removeSuffix("/")
        if (trimmed.isBlank()) error("Base URL cannot be empty")
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "http://$trimmed"
        }

        return withScheme.trimEnd('/')
    }

    private fun generateId(nowMs: Long): String {
        // Use a random suffix to avoid shared mutable state and prevent ID collisions across threads/coroutines.
        val suffix = Random.nextLong().toULong().toString(16).padStart(16, '0')
        return "srv-$nowMs-$suffix"
    }
}
