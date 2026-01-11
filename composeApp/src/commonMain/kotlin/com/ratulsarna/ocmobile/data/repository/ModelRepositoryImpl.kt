package com.ratulsarna.ocmobile.data.repository

import com.ratulsarna.ocmobile.data.api.OpenCodeApi
import com.ratulsarna.ocmobile.data.dto.ModelDto
import com.ratulsarna.ocmobile.data.dto.ProviderDto
import com.ratulsarna.ocmobile.data.settings.AppSettings
import com.ratulsarna.ocmobile.domain.error.NetworkError
import com.ratulsarna.ocmobile.domain.model.Model
import com.ratulsarna.ocmobile.domain.model.Provider
import com.ratulsarna.ocmobile.domain.model.SelectedModel
import com.ratulsarna.ocmobile.domain.repository.ModelRepository
import kotlinx.coroutines.flow.Flow

/**
 * Implementation of ModelRepository using OpenCode API and AppSettings.
 */
class ModelRepositoryImpl(
    private val api: OpenCodeApi,
    private val appSettings: AppSettings
) : ModelRepository {

    override suspend fun getConnectedProviders(): Result<List<Provider>> {
        return runCatching {
            val response = api.getProviders()
            val connectedIds = response.connected.toSet()

            // Filter to only connected providers and map to domain models
            val providers = response.all
                .filter { it.id in connectedIds }
                .map { it.toDomain() }
                .filter { it.models.isNotEmpty() }
                .sortedBy { it.name }

            // Ensure there is always a selected model after a fresh connect/reset.
            initializeDefaultModelIfNeeded(providers)

            pruneFavoriteModelsIfNeeded(providers)

            providers
        }.recoverCatching { e ->
            throw NetworkError(message = e.message, cause = e)
        }
    }

    /**
     * Initialize the selected model from server's global config if no local selection exists.
     *
     * Fetches from /config endpoint which returns the server's default model
     * in format "providerId/modelId" (e.g., "anthropic/claude-opus-4-5").
     */
    private suspend fun initializeDefaultModelIfNeeded(connectedProviders: List<Provider>) {
        // Only initialize if no local selection exists
        val currentSelection = appSettings.getSelectedModelSnapshot()
        if (currentSelection != null) return

        // Fetch the global default model from server config
        val configModel = runCatching { api.getConfig().model }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        // Try the server's global default first.
        if (configModel != null) {
            val parts = configModel.split("/", limit = 2)
            if (parts.size == 2) {
                val (providerId, modelId) = parts
                val parsed = SelectedModel(providerId, modelId)
                val isAvailable = connectedProviders
                    .any { provider -> provider.id == providerId && provider.models.any { it.id == modelId } }
                if (isAvailable) {
                    appSettings.setSelectedModel(parsed)
                    return
                }
            }
        }

        // Fallback: pick the first available model from connected providers.
        val fallbackProvider = connectedProviders.firstOrNull() ?: return
        val fallbackModel = fallbackProvider.models.firstOrNull() ?: return
        appSettings.setSelectedModel(SelectedModel(fallbackProvider.id, fallbackModel.id))
    }

    override fun getSelectedModel(): Flow<SelectedModel?> {
        return appSettings.getSelectedModel()
    }

    override suspend fun setSelectedModel(model: SelectedModel?) {
        appSettings.setSelectedModel(model)
    }

    override fun getFavoriteModels(): Flow<List<SelectedModel>> {
        return appSettings.getFavoriteModels()
    }

    override suspend fun setFavoriteModels(models: List<SelectedModel>) {
        appSettings.setFavoriteModels(models)
    }

    override suspend fun toggleFavoriteModel(model: SelectedModel) {
        val current = appSettings.getFavoriteModelsSnapshot()
        val key = "${model.providerId}:${model.modelId}"
        val updated = if (current.any { "${it.providerId}:${it.modelId}" == key }) {
            current.filterNot { "${it.providerId}:${it.modelId}" == key }
        } else {
            current + model
        }
        appSettings.setFavoriteModels(updated)
    }

    private suspend fun pruneFavoriteModelsIfNeeded(connectedProviders: List<Provider>) {
        val availableKeys = connectedProviders
            .flatMap { provider -> provider.models.map { model -> "${provider.id}:${model.id}" } }
            .toSet()

        val current = appSettings.getFavoriteModelsSnapshot()
        val pruned = current.filter { "${it.providerId}:${it.modelId}" in availableKeys }

        if (pruned.size != current.size) {
            appSettings.setFavoriteModels(pruned)
        }
    }
}

/**
 * Extension function to map ProviderDto to domain model.
 */
internal fun ProviderDto.toDomain(): Provider = Provider(
    id = id,
    name = name,
    models = models.values.map { it.toDomain(id) }.sortedBy { it.name }
)

/**
 * Extension function to map ModelDto to domain model.
 */
internal fun ModelDto.toDomain(providerId: String): Model = Model(
    id = id,
    providerId = providerId,
    name = name,
    contextLimit = limit?.context,
    reasoningCapable = capabilities?.reasoning ?: false,
    variants = variants?.keys?.sorted().orEmpty()
)
