package me.rerere.rikkahub.data.ai

import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.ChatImageGenerationSettings
import me.rerere.rikkahub.data.model.resolveChatImageModel
import me.rerere.rikkahub.data.model.toParams
import me.rerere.rikkahub.data.model.constrained
import kotlin.uuid.Uuid

data class ChatImageGenerationResult(
    val images: List<UIMessagePart.Image>,
    val modelId: Uuid,
    val seed: Long?,
)

class ChatImageGenerationService(
    private val providerManager: ProviderManager,
    private val filesManager: FilesManager,
    private val generationKeepAlive: me.rerere.rikkahub.service.GenerationKeepAlive? = null,
) {
    suspend fun generate(settings: Settings, prompt: String, options: ChatImageGenerationSettings = ChatImageGenerationSettings()): ChatImageGenerationResult = withContext(Dispatchers.IO) {
        kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.let { generationKeepAlive?.track(it) }
        require(prompt.isNotBlank()) { "Image prompt cannot be empty" }
        val model = settings.resolveChatImageModel() ?: error("No enabled image generation model is configured")
        val provider = model.findProvider(settings.providers) ?: error("Image generation provider is unavailable")
        val constraints = providerManager.imageGenerationConstraints(provider, model)
        check(constraints.supportsGeneration) { "Image generation is not supported" }
        generate(provider, options.copy(prompt = prompt).constrained(constraints).toParams(model))
    }

    /** Execute the exact confirmed request; tool callers persist each final image as it is saved. */
    suspend fun generate(
        provider: ProviderSetting,
        params: ImageGenerationParams,
        onImageSaved: suspend (UIMessagePart.Image) -> Unit = {},
    ): ChatImageGenerationResult = withContext(Dispatchers.IO) {
        require(params.prompt.isNotBlank()) { "Image prompt cannot be empty" }
        kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.let { generationKeepAlive?.track(it) }
        val images = mutableListOf<UIMessagePart.Image>()
        var seed: Long? = null
        providerManager.getProviderByType(provider).generateImage(provider, params).collect { item ->
            if (!item.partial && (item.data.isNotBlank() || !item.temporaryFilePath.isNullOrBlank())) {
                val image = UIMessagePart.Image(materialize(item).toString())
                onImageSaved(image)
                images += image
                if (seed == null) seed = item.seed
            }
        }
        check(images.isNotEmpty()) { "Image provider returned no final image" }
        ChatImageGenerationResult(images, params.model.id, seed)
    }

    private fun materialize(item: ImageGenerationItem): Uri {
        item.temporaryFilePath?.takeIf(String::isNotBlank)?.let { path ->
            val file = java.io.File(path)
            if (!file.isFile || file.length() == 0L) error("Generated image file is unavailable")
            return filesManager.createChatFilesByByteArrays(
                byteArrays = listOf(file.readBytes()),
                displayName = "generated-image",
                mimeType = item.mimeType.ifBlank { "image/png" },
            ).firstOrNull()
                ?: error("Unable to save generated image")
        }
        val encoded = item.data.substringAfter("base64,", item.data)
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        check(bytes.isNotEmpty()) { "Generated image payload is empty" }
        val mimeType = item.mimeType.ifBlank { "image/png" }
        return filesManager.createChatFilesByByteArrays(
            byteArrays = listOf(bytes),
            displayName = "generated-image",
            mimeType = mimeType,
        ).firstOrNull()
            ?: error("Unable to save generated image")
    }
}
