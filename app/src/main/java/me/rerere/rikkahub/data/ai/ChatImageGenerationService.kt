package me.rerere.rikkahub.data.ai

import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.ProviderManager
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
        val items = providerManager.getProviderByType(provider).generateImage(
            provider,
            options.copy(prompt = prompt).constrained(constraints).toParams(model),
        ).toList().filter { it.data.isNotBlank() || !it.temporaryFilePath.isNullOrBlank() }
        check(items.isNotEmpty()) { "Image provider returned no image" }
        val images = items.map { item -> UIMessagePart.Image(materialize(item).toString()) }
        ChatImageGenerationResult(images, model.id, items.firstOrNull()?.seed)
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
