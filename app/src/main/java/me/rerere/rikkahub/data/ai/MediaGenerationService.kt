package me.rerere.rikkahub.data.ai

import me.rerere.ai.provider.*
import me.rerere.rikkahub.data.model.VideoGenerationRequestState
import me.rerere.rikkahub.data.video.VideoGenerationCoordinator

/** Shared submission boundary; video work remains durable and independent of a screen. */
class MediaGenerationService(
    private val providers: ProviderManager,
    private val videos: VideoGenerationCoordinator,
) {
    suspend fun generateImage(provider: ProviderSetting, params: ImageGenerationParams) =
        providers.getProviderByType(provider).generateImage(provider, params)

    suspend fun editImage(provider: ProviderSetting, params: ImageEditParams) =
        providers.getProviderByType(provider).editImage(provider, params)

    suspend fun generateVideo(provider: ProviderSetting, model: Model, request: VideoGenerationRequestState) =
        videos.createTask(provider, model, request)
}
