package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class BackgroundChatModelResolverTest {
    @Test
    fun `preferred enabled chat model is selected`() {
        val preferred = Model(modelId = "preferred")
        val fast = Model(modelId = "fast")
        val settings = Settings(
            providers = listOf(ProviderSetting.OpenAI(models = listOf(fast, preferred))),
            fastModelId = fast.id,
        )

        assertEquals(preferred.id, settings.resolveBackgroundChatModel(preferred.id)?.id)
    }

    @Test
    fun `disabled preferred model falls back to enabled fast model`() {
        val disabledPreferred = Model(modelId = "disabled-preferred")
        val fast = Model(modelId = "fast")
        val settings = Settings(
            providers = listOf(
                ProviderSetting.OpenAI(enabled = false, models = listOf(disabledPreferred)),
                ProviderSetting.Google(enabled = true, models = listOf(fast)),
            ),
            fastModelId = fast.id,
        )

        assertEquals(fast.id, settings.resolveBackgroundChatModel(disabledPreferred.id)?.id)
    }

    @Test
    fun `non chat selections are ignored`() {
        val image = Model(modelId = "image", type = ModelType.IMAGE)
        val chat = Model(modelId = "chat", type = ModelType.CHAT)
        val settings = Settings(
            providers = listOf(ProviderSetting.OpenAI(models = listOf(image, chat))),
            fastModelId = image.id,
            chatModelId = chat.id,
        )

        assertEquals(chat.id, settings.resolveBackgroundChatModel(image.id)?.id)
    }

    @Test
    fun `no enabled chat model returns null`() {
        val disabledChat = Model(modelId = "disabled")
        val settings = Settings(
            providers = listOf(
                ProviderSetting.OpenAI(enabled = false, models = listOf(disabledChat)),
                ProviderSetting.Google(
                    enabled = true,
                    models = listOf(Model(modelId = "embedding", type = ModelType.EMBEDDING)),
                ),
            ),
            fastModelId = Uuid.random(),
            chatModelId = Uuid.random(),
        )

        assertNull(settings.resolveBackgroundChatModel(disabledChat.id))
    }
}
