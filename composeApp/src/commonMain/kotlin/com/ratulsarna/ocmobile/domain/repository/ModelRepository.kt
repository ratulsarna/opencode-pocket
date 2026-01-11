package com.ratulsarna.ocmobile.domain.repository

import com.ratulsarna.ocmobile.domain.model.Provider
import com.ratulsarna.ocmobile.domain.model.SelectedModel
import kotlinx.coroutines.flow.Flow

/**
 * Repository for model and provider operations.
 */
interface ModelRepository {
    /**
     * Get all connected providers with their models.
     * Only returns providers that are currently connected (have API keys).
     */
    suspend fun getConnectedProviders(): Result<List<Provider>>

    /**
     * Get the currently selected model as a flow.
     * Emits null if no model is selected.
     */
    fun getSelectedModel(): Flow<SelectedModel?>

    /**
     * Set the selected model.
     * @param model The model to select, or null to clear selection
     */
    suspend fun setSelectedModel(model: SelectedModel?)

    /**
     * Get the user's favorited models.
     * Favorites affect model selection UI ordering only (they do not select a model).
     */
    fun getFavoriteModels(): Flow<List<SelectedModel>>

    /**
     * Persist the user's favorited models.
     */
    suspend fun setFavoriteModels(models: List<SelectedModel>)

    /**
     * Toggle a model as favorited/unfavorited.
     */
    suspend fun toggleFavoriteModel(model: SelectedModel)
}
