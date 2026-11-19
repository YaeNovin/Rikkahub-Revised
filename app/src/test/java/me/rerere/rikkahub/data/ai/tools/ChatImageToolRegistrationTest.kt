package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.*
import me.rerere.ai.ui.AskUserProtocol
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.buildAskUserTool
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ChatImageToolRegistrationTest {
    private val image = Model(modelId = "configured-image", type = ModelType.IMAGE)
    private val visionChat = Model(abilities = listOf(ModelAbility.TOOL), inputModalities = listOf(Modality.TEXT, Modality.IMAGE))
    private val provider = ProviderSetting.OpenAI(models = listOf(image))
    private val settings = Settings(providers = listOf(provider), imageGenerationModelId = image.id)
    private val assistant = Assistant(enableImageGenerationTool = true)
    private val supported = ImageGenerationConstraints(supportsGeneration = true, supportsEdit = false, supportsPartialImages = false)

    private fun register(
        settings: Settings = this.settings,
        assistant: Assistant = this.assistant,
        model: Model = visionChat,
        hasConversation: Boolean = true,
        base: List<Tool> = emptyList(),
        constraints: ImageGenerationConstraints = supported,
    ) = base.withChatImageTools(settings, assistant, model, hasConversation,
        constraintsFor = { _, _ -> constraints }, inspect = { listOf(UIMessagePart.Text(it)) })

    @Test fun `configured image model uses standard tool schema and task bound execution`() = runBlocking {
        val tools = register()
        assertEquals(setOf(CHAT_IMAGE_TOOL_NAME, INSPECT_GENERATED_IMAGE_TOOL_NAME, AskUserProtocol.TOOL_NAME), tools.map { it.name }.toSet())
        val generate = tools.single { it.name == CHAT_IMAGE_TOOL_NAME }
        assertEquals("generate_image", generate.name) // Stable persisted call name; do not add plural aliases.
        val schema = generate.parameters() as InputSchema.Obj
        assertEquals(listOf("prompt"), schema.required)
        assertEquals(setOf("prompt"), schema.properties.keys)
        assertEquals("12000", schema.properties.getValue("prompt").jsonObject.getValue("maxLength").jsonPrimitive.content)
        val input = buildJsonObject { put("prompt", "bird") }
        assertTrue(generate.needsApproval(input))
        assertTrue(runCatching { generate.execute(input) }.isFailure)
        val inspected = tools.single { it.name == INSPECT_GENERATED_IMAGE_TOOL_NAME }
            .execute(buildJsonObject { put("task_id", "owned-task") }).single() as UIMessagePart.Text
        assertEquals("owned-task", inspected.text)
    }

    @Test fun `no selected image model exposes neither image tools nor automatic ask user`() {
        assertTrue(register(settings.copy(imageGenerationModelId = Uuid.random())).isEmpty())
        assertTrue(register(settings.copy(providers = emptyList())).isEmpty())
        assertTrue(register(settings.copy(imageGenerationModelId = Uuid.random(), imageGenerationPageModelId = image.id)).isEmpty())
    }

    @Test fun `disabled provider wrong model type and unsupported generation hide the tools`() {
        assertTrue(register(settings.copy(providers = listOf(provider.copy(enabled = false)))).isEmpty())
        assertTrue(register(settings.copy(providers = listOf(provider.copy(models = listOf(image.copy(type = ModelType.CHAT)))))).isEmpty())
        assertTrue(register(settings.copy(providers = listOf(provider.copy(models = listOf(image.copy(modelId = " ")))))).isEmpty())
        assertTrue(register(constraints = supported.copy(supportsGeneration = false)).isEmpty())
    }

    @Test fun `assistant opt out missing conversation and non tool model expose no image tools`() {
        assertTrue(register(assistant = assistant.copy(enableImageGenerationTool = false, autoApproveImageGeneration = true)).isEmpty())
        assertTrue(register(hasConversation = false).isEmpty())
        assertTrue(register(model = visionChat.copy(abilities = emptyList())).isEmpty())
    }

    @Test fun `capability checks use the effective image route not the chat model`() {
        val override = ProviderSetting.OpenAI(baseUrl = "https://images.invalid", models = emptyList())
        val routedImage = image.copy(providerOverwrite = override)
        val configured = settings.copy(providers = listOf(provider.copy(models = listOf(routedImage))))
        var checked = false
        val tools = emptyList<Tool>().withChatImageTools(configured, assistant, visionChat, true,
            constraintsFor = { actualProvider, actualModel ->
                assertEquals(override.id, actualProvider.id)
                assertEquals(routedImage, actualModel)
                checked = true
                supported
            }, inspect = { emptyList() })
        assertTrue(checked)
        assertTrue(tools.any { it.name == CHAT_IMAGE_TOOL_NAME })
        assertTrue(register(configured.copy(providers = listOf(provider.copy(enabled = false, models = listOf(routedImage))))).isEmpty())
        assertTrue(register(configured.copy(providers = listOf(provider.copy(models = listOf(routedImage.copy(
            providerOverwrite = override.copy(enabled = false))))))).isEmpty())
    }

    @Test fun `non vision model receives generation and clarification but no inspection declaration`() {
        val tools = register(model = visionChat.copy(inputModalities = listOf(Modality.TEXT)))
        assertEquals(setOf(CHAT_IMAGE_TOOL_NAME, AskUserProtocol.TOOL_NAME), tools.map { it.name }.toSet())
        assertFalse(tools.joinToString { it.description }.contains(INSPECT_GENERATED_IMAGE_TOOL_NAME))
    }

    @Test fun `explicit generic ask user is unchanged and never gains image options`() {
        val ask = buildAskUserTool()
        val unrelated = Tool("get_time", "time", execute = { emptyList() })
        val base = listOf(ask, unrelated)
        val disabled = register(settings.copy(imageGenerationModelId = Uuid.random()), base = base)
        assertEquals(base, disabled)
        val enabled = register(base = base)
        assertEquals(1, enabled.count { it.name == AskUserProtocol.TOOL_NAME })
        assertSame(ask, enabled.single { it.name == AskUserProtocol.TOOL_NAME })
        assertFalse(ask.description.contains(CHAT_IMAGE_TOOL_NAME))
        assertFalse(ask.parameters().toString().contains(CHAT_IMAGE_TOOL_NAME))
        assertFalse(ask.parameters().toString().contains("image_generation"))
    }

    @Test fun `unconfigured registration removes stale image definitions`() {
        val stale = listOf(chatImageToolDefinition(), inspectGeneratedImageTool { emptyList() })
        assertTrue(register(settings.copy(imageGenerationModelId = Uuid.random()), base = stale).isEmpty())
        assertEquals(1, register(base = stale).count { it.name == CHAT_IMAGE_TOOL_NAME })
    }

    @Test fun `capability discovery mirrors the actual manifest when image model is removed`() = runBlocking {
        val withoutImages = settings.copy(imageGenerationModelId = Uuid.random())
        val registered = register(withoutImages)
        val discovery = createSessionCapabilitiesTool(assistant, true, { registered.map { it.name } })
        val output = discovery.execute(buildJsonObject { put("include_ask_user_details", true) }).single() as UIMessagePart.Text
        val payload = Json.parseToJsonElement(output.text).jsonObject
        assertEquals(buildJsonObject { put("enabled", false); put("status", "disabled") }, payload["image_generation"])
        assertEquals(buildJsonObject { put("enabled", false) }, payload["ask_user"])
        assertEquals(JsonArray(emptyList()), payload["availableTools"])
        assertFalse(output.text.contains(CHAT_IMAGE_TOOL_NAME))
        assertFalse(output.text.contains(INSPECT_GENERATED_IMAGE_TOOL_NAME))
    }
}
