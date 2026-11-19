package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.*
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.*
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class SessionCapabilitiesContextTest {
    private val image = Model(modelId = "configured-image", type = ModelType.IMAGE)
    private val chat = Model(abilities = listOf(ModelAbility.TOOL), inputModalities = listOf(Modality.TEXT, Modality.IMAGE))
    private val provider = ProviderSetting.OpenAI(models = listOf(image))
    private val settings = Settings(providers = listOf(provider), imageGenerationModelId = image.id)

    @Test fun `web search discovery follows actual declarations and native request state`() = runBlocking {
        for ((names, native, enabled) in listOf(
            Triple(emptyList<String>(), false, false),
            Triple(listOf("search_web"), false, true),
            Triple(emptyList<String>(), true, true),
        )) {
            val tool = createSessionCapabilitiesTool(
                assistant = Assistant(enableWebSearch = !enabled),
                toolCallsAvailable = true,
                availableToolNames = { names },
                searchCapabilities = buildJsonObject { put("native", native) },
            )
            val payload = Json.parseToJsonElement((tool.execute(buildJsonObject {}).single() as UIMessagePart.Text).text).jsonObject
            assertEquals(enabled, payload["features"]!!.jsonObject["webSearch"]!!.jsonPrimitive.boolean)
            assertEquals(native, payload["search"]!!.jsonObject["native"]!!.jsonPrimitive.boolean)
        }
    }

    private fun manifest(enabled: Boolean, settings: Settings = this.settings, model: Model = chat): List<Tool> =
        emptyList<Tool>().withRenderingTools(true).withChatImageTools(
            settings, Assistant(enableImageGenerationTool = enabled), model, true,
            constraintsFor = { _, _ -> ImageGenerationConstraints(supportsGeneration = true, supportsEdit = false, supportsPartialImages = false) },
            inspect = { error("Capability refresh must not inspect or generate images") },
        ).withSessionCapabilitiesTool(Assistant(enableImageGenerationTool = enabled))

    private fun List<Tool>.discovery() = single { it.name == SESSION_CAPABILITIES_TOOL_NAME }
    private fun UIMessagePart.Tool.payload() = Json.parseToJsonElement((output.single() as UIMessagePart.Text).text).jsonObject
    private fun List<UIMessage>.queryPart() = flatMap { it.getTools() }.first { it.toolName == SESSION_CAPABILITIES_TOOL_NAME }
    private suspend fun history(tool: Tool, input: String = "{}") = listOf(
        UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("有哪些工具"))),
        UIMessage.assistant("").copy(parts = listOf(UIMessagePart.Tool(
            toolCallId = "old-capability-query", toolName = SESSION_CAPABILITIES_TOOL_NAME,
            input = input, output = tool.execute(Json.parseToJsonElement(input)),
            metadata = buildJsonObject { put("opaque_provider_metadata", "preserved-signature") },
        ), UIMessagePart.Text("此前的图片生成未启用。"))),
        UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("现在是否能找到图片生成接口"))),
    )

    @Test fun `disabled historical result becomes current enabled state without another model query`() = runBlocking {
        val original = history(manifest(false).discovery())
        assertFalse(original.queryPart().payload()["image_generation"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean)
        val current = manifest(true)
        val request = original.withCurrentSessionCapabilities(current.discovery())
        val payload = request.queryPart().payload()
        assertEquals("current_request", payload["snapshot_scope"]!!.jsonPrimitive.content)
        assertTrue(payload["image_generation"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean)
        assertEquals(current.map { it.name }.sorted(), payload["availableTools"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(CHAT_IMAGE_TOOL_NAME in current.map { it.name })
        assertTrue(INSPECT_GENERATED_IMAGE_TOOL_NAME in current.map { it.name })
        // Only the outgoing request copy changes; stored messages, prose and call bindings do not.
        assertFalse(original.queryPart().payload()["image_generation"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean)
        assertEquals(original.queryPart().toolCallId, request.queryPart().toolCallId)
        assertEquals(original.queryPart().input, request.queryPart().input)
        assertSame(original.queryPart().metadata, request.queryPart().metadata)
        assertEquals(original.queryPart().approvalState, request.queryPart().approvalState)
        assertEquals(original.map { it.id }, request.map { it.id })
        assertEquals(original[1].parts.last(), request[1].parts.last())
    }

    @Test fun `disabling image tools or removing configuration clears old enabled rules`() = runBlocking {
        val original = history(manifest(true).discovery())
        for (current in listOf(manifest(false), manifest(true, settings.copy(imageGenerationModelId = Uuid.random())))) {
            val payload = original.withCurrentSessionCapabilities(current.discovery()).queryPart().payload()
            assertEquals(buildJsonObject { put("enabled", false); put("status", "disabled") }, payload["image_generation"])
            assertFalse(payload.toString().contains(CHAT_IMAGE_TOOL_NAME))
            assertFalse(payload.toString().contains(INSPECT_GENERATED_IMAGE_TOOL_NAME))
        }
    }

    @Test fun `switching to text only chat refreshes inspection without changing manual parameter authority`() = runBlocking {
        val original = history(manifest(true).discovery())
        val current = manifest(true, model = chat.copy(inputModalities = listOf(Modality.TEXT)))
        val imageState = original.withCurrentSessionCapabilities(current.discovery()).queryPart().payload()["image_generation"]!!.jsonObject
        assertEquals("unavailable", imageState["inspection"]!!.jsonPrimitive.content)
        assertTrue(imageState["parameters_selected_by_user"]!!.jsonPrimitive.boolean)
        assertTrue(imageState["requires_user_confirmation"]!!.jsonPrimitive.boolean)
        assertFalse(imageState["ask_user_changes_parameters"]!!.jsonPrimitive.boolean)
        assertFalse(INSPECT_GENERATED_IMAGE_TOOL_NAME in current.map { it.name })
    }

    @Test fun `request refresh is idempotent and works after reloading original history for reconnect`() = runBlocking {
        val original = history(manifest(false).discovery())
        val discovery = manifest(true).discovery()
        val first = original.withCurrentSessionCapabilities(discovery)
        assertEquals(first, first.withCurrentSessionCapabilities(discovery))
        assertEquals(first, original.withCurrentSessionCapabilities(discovery))
    }

    @Test fun `pending capabilities and all other tool outputs are untouched and never executed`() = runBlocking {
        val pending = UIMessagePart.Tool("pending", SESSION_CAPABILITIES_TOOL_NAME, "{}", approvalState = ToolApprovalState.Pending)
        val paid = UIMessagePart.Tool("image", CHAT_IMAGE_TOOL_NAME, "{}", listOf(UIMessagePart.Text("saved image result")))
        val original = listOf(UIMessage.assistant("").copy(parts = listOf(pending, paid)))
        val guard = Tool(SESSION_CAPABILITIES_TOOL_NAME, "guard", execute = { error("No completed query to refresh") })
        assertSame(original, original.withCurrentSessionCapabilities(guard))
    }

    @Test fun `a missing discovery interface invalidates historical success rather than inventing availability`() = runBlocking {
        val original = history(manifest(true).discovery())
        val payload = original.withCurrentSessionCapabilities(null).queryPart().payload()
        assertEquals("historical_not_current", payload["snapshot_scope"]!!.jsonPrimitive.content)
        assertFalse("image_generation" in payload)
        assertFalse(payload.toString().contains(CHAT_IMAGE_TOOL_NAME))
    }

    @Test fun `refresh failure hides stale state and cancellation propagates`() = runBlocking {
        val original = history(manifest(true).discovery())
        val failed = Tool(SESSION_CAPABILITIES_TOOL_NAME, "test", execute = { error("private diagnostic") })
        val payload = original.withCurrentSessionCapabilities(failed).queryPart().payload()
        assertEquals("historical_not_current", payload["snapshot_scope"]!!.jsonPrimitive.content)
        assertFalse(payload.toString().contains("private diagnostic"))
        val cancelled = failed.copy(execute = { throw CancellationException("stop") })
        assertTrue(runCatching { original.withCurrentSessionCapabilities(cancelled) }.exceptionOrNull() is CancellationException)
    }

    @Test fun `refresh respects requested detail flags and evaluates each duplicate query only once`() = runBlocking {
        val input = """{"include_ask_user_details":true,"include_suggestion_details":true}"""
        val original = history(manifest(false).discovery(), input)
        val current = manifest(true).discovery()
        var calls = 0
        val counted = current.copy(execute = { args ->
            calls++
            assertTrue(args.jsonObject["include_suggestion_details"]!!.jsonPrimitive.boolean)
            current.execute(args)
        })
        val duplicate = original[1].copy(id = Uuid.random(), parts = listOf(original.queryPart().copy(toolCallId = "another-query")))
        val request = (original + duplicate).withCurrentSessionCapabilities(counted)
        assertEquals(1, calls)
        assertTrue("examples" in request.queryPart().payload()["ask_user"]!!.jsonObject)
        assertEquals("another-query", request.last().getTools().single().toolCallId)
    }

    @Test fun `canonical discovery replaces stale caller definitions and binds to final manifest`() = runBlocking {
        val stale = manifest(false).discovery()
        val current = manifest(true).filterNot { it.name == SESSION_CAPABILITIES_TOOL_NAME }
            .plus(listOf(stale, stale)).withSessionCapabilitiesTool(Assistant(enableImageGenerationTool = true))
        assertEquals(1, current.count { it.name == SESSION_CAPABILITIES_TOOL_NAME })
        val result = history(current.discovery()).queryPart().payload()
        assertTrue(result["image_generation"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean)
        assertEquals(current.map { it.name }.sorted(), result["availableTools"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun `fresh conversations have no refresh prompt or execution and old claims get explicit freshness guidance`() = runBlocking {
        val discovery = manifest(true).discovery()
        val fresh = listOf(UIMessage.user("hello"))
        assertSame(fresh, fresh.withCurrentSessionCapabilities(discovery))
        assertEquals("", discovery.systemPrompt(chat, fresh))
        val original = history(manifest(false).discovery())
        assertTrue(discovery.systemPrompt(chat, original).contains("snapshot_scope=current_request"))
        assertFalse(discovery.systemPrompt(chat, original).contains(CHAT_IMAGE_TOOL_NAME))
    }
}
