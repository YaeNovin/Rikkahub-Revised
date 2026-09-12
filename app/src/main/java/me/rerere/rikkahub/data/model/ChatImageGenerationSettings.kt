package me.rerere.rikkahub.data.model

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider

/** Temporary, conversation-local image options. It deliberately never writes global settings. */
typealias ChatImageGenerationSettings = ImageGenerationRequestState

fun Settings.resolveChatImageModel(): Model? =
    findModelById(imageGenerationModelId)
        ?.takeIf { it.type == ModelType.IMAGE }
        ?.takeIf { it.findProvider(providers)?.enabled == true }
