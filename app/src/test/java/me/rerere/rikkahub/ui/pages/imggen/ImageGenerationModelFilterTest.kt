package me.rerere.rikkahub.ui.pages.imggen

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageGenerationModelFilterTest {
    @Test
    fun `only explicitly configured image models appear in image generation`() {
        val provider = ProviderSetting.OpenAI(
            models = listOf(
                Model(modelId = "gpt-5", type = ModelType.CHAT),
                Model(modelId = "gpt-image-1", type = ModelType.IMAGE),
                Model(modelId = "text-embedding-3-large", type = ModelType.EMBEDDING),
            )
        )

        assertEquals(
            listOf("gpt-image-1"),
            provider.configuredImageModels().map(Model::modelId),
        )
    }
}
