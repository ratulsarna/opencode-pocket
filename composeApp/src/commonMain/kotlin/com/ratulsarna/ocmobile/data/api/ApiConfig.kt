package com.ratulsarna.ocmobile.data.api

/**
 * API configuration for OpenCode backend.
 */
object ApiConfig {
    /**
     * OpenCode API base URL (chat, sessions, messages).
     *
     * This is a safe placeholder default (useful for local development / simulator).
     * Real usage should come from the paired server configuration in-app.
     */
    const val OPENCODE_API_BASE_URL = "http://127.0.0.1:3001"

    /**
     * Display-friendly endpoint without protocol prefix.
     * Used in UI (e.g., Settings screen).
     */
    val openCodeDisplayEndpoint: String
        get() = OPENCODE_API_BASE_URL.removePrefix("http://").removePrefix("https://")
}
