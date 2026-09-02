package me.rerere.rikkahub.ui.pages.imggen

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting

internal fun ProviderSetting.configuredImageModels(): List<Model> =
    models.filter { it.type == ModelType.IMAGE }
