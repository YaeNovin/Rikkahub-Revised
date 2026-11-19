package me.rerere.ai.provider

import kotlinx.serialization.json.*
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.providers.openai.OpenAIProvider
import me.rerere.ai.registry.ModelRegistry
import org.junit.Assert.*
import org.junit.Test
import okhttp3.OkHttpClient

class ModelNameMatchingTest {
    @Test fun `legacy opaque deployments gain name metadata while manual limits stay intact`() {
        val stored = Model(modelId = "deployment-42", displayName = "Gemini 4 Argon")
        val migrated = stored.withNameBasedCapabilities()
        assertTrue(ModelAbility.TOOL in migrated.abilities)
        assertTrue(Modality.IMAGE in migrated.inputModalities)
        assertEquals(1_048_576, migrated.contextWindowTokens)
        assertEquals(9999, stored.copy(contextWindowTokens = 9999).withNameBasedCapabilities().contextWindowTokens)
        assertEquals(stored.id, migrated.id)
        assertEquals("deployment-42", migrated.modelId)
    }

    @Test fun `human readable names identify capabilities and parameters without replacing deployment IDs`() {
        val model = Model(modelId = "deployment-42", displayName = "Google Gemini 4 Argon").withDetectedCapabilities()
        assertEquals("gemini-4-argon", model.parameterModelId())
        assertEquals("deployment-42", model.modelId)
        assertTrue(ModelAbility.TOOL in model.abilities)
        assertTrue(Modality.IMAGE in model.inputModalities)
        assertEquals(listOf(ReasoningLevel.AUTO, ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH),
            resolveReasoningLevelSupport(model, ProviderSetting.Google()).levels)
        assertEquals(1_048_576, model.contextWindowTokens)
    }

    @Test fun `recognized name wins consistently while a nickname falls back to the API ID`() {
        val model = Model(modelId = "gpt-4o", displayName = "Gemini 4 Argon")
        assertEquals(ModelParameterFamily.GEMINI, model.inferParameterFamily())
        assertEquals("gemini-4-argon", model.parameterModelId())
        assertTrue(ModelRegistry.MODEL_ABILITIES.getData(model).contains(ModelAbility.REASONING))
        assertEquals("gpt-4o", model.copy(displayName = "我的助手").parameterModelId())
    }

    @Test fun `renaming updates automatic metadata and preserves explicit overrides`() {
        val old = Model(modelId = "opaque", displayName = "GPT 4o").withDetectedCapabilities()
        val renamed = old.withDisplayName("Gemini 4 Argon")
        assertEquals("opaque", renamed.modelId)
        assertTrue(ModelAbility.REASONING in renamed.abilities)
        assertEquals(1_048_576, renamed.contextWindowTokens)
        val manual = old.copy(inputModalities = listOf(Modality.TEXT), contextWindowTokens = 12345)
            .withDisplayName("Gemini 4 Argon")
        assertEquals(listOf(Modality.TEXT), manual.inputModalities)
        assertEquals(12345, manual.contextWindowTokens)
    }

    @Test fun `image capabilities follow names including spaces and mixed case`() {
        val model = Model(modelId = "image-deployment", displayName = "OpenAI GPT Image 2.6 Sunburst").withDetectedCapabilities()
        assertEquals(ModelType.IMAGE, model.type)
        val limits = OpenAIProvider(OkHttpClient()).imageGenerationConstraints(ProviderSetting.OpenAI(), model)
        assertTrue(limits.supportsGptImage26Options)
        assertTrue("svg" in limits.supportedOutputFormats)
        val body = me.rerere.ai.provider.providers.openai.buildOpenAIImageGenerationRequestBody(
            ImageGenerationParams(model, "fixture", outputFormat = "svg", numOfImages = 3), limits)
        assertEquals("image-deployment", body["model"]!!.jsonPrimitive.content)
        assertEquals(1, body["n"]!!.jsonPrimitive.int)
    }
}
