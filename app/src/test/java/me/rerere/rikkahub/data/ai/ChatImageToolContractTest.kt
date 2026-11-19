package me.rerere.rikkahub.data.ai

import me.rerere.ai.provider.ImageGenerationConstraints
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Modality
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.*
import me.rerere.rikkahub.data.db.entity.ChatImageToolTaskEntity
import me.rerere.rikkahub.data.model.ImageGenerationRequestState
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ChatImageToolContractTest {
    @Test fun `adding a trusted task id preserves provider tool replay metadata`() {
        val original = JsonInstant.parseToJsonElement("""{"thoughtSignature":"opaque-signature","functionCallId":"provider-call","image_generation_task_id":"untrusted"}""") as kotlinx.serialization.json.JsonObject
        val updated = imageToolPendingMetadata(original, "trusted-task")
        assertEquals(original["thoughtSignature"], updated["thoughtSignature"])
        assertEquals(original["functionCallId"], updated["functionCallId"])
        assertEquals(kotlinx.serialization.json.JsonPrimitive("trusted-task"), updated[CHAT_IMAGE_TOOL_TASK_KEY])
    }

    @Test fun `confirmation is bound to its exact conversation response call and input`() {
        val conversation = Uuid.random()
        val message = Uuid.random()
        val tool = UIMessagePart.Tool("call", CHAT_IMAGE_TOOL_NAME, """{"prompt":"a blue bird"}""")
        val task = ChatImageToolTaskEntity("task", conversation.toString(), message.toString(), "source", tool.toolCallId,
            imageToolHash(tool.input), "真实用户原文", "a blue bird", "{}", createdAt = 0, updatedAt = 0)
        validateImageToolBinding(task, conversation, message, tool)
        assertTrue(runCatching { validateImageToolBinding(task, Uuid.random(), message, tool) }.isFailure)
        assertTrue(runCatching { validateImageToolBinding(task, conversation, Uuid.random(), tool) }.isFailure)
        assertTrue(runCatching { validateImageToolBinding(task, conversation, message, tool.copy(input = """{"prompt":"different"}""")) }.isFailure)
        assertTrue(runCatching { validateImageToolBinding(task, conversation, message, tool.copy(toolCallId = "other")) }.isFailure)
    }

    @Test fun `advanced options survive a persistent request round trip`() {
        val options = ImageGenerationRequestState(prompt = "最终确认提示词", count = 2, size = "1024x1024",
            quality = "high", seed = 123, steps = 25, guidanceScale = 4.5f, negativePrompt = "blur", outputCompression = 80)
        val request = ChatImageToolRequest("model", "provider", "Model", "Provider", "hash", options)
        assertEquals(request, JsonInstant.decodeFromString<ChatImageToolRequest>(JsonInstant.encodeToString(request)))
    }

    @Test fun `non vision wire context omits generated images without changing stored messages`() {
        val image = UIMessagePart.Image("file:///generated.png")
        val tool = UIMessagePart.Tool("call", CHAT_IMAGE_TOOL_NAME, "{}", output = listOf(UIMessagePart.Text("saved"), image))
        val messages = listOf(UIMessage.assistant("ready").copy(parts = listOf(tool)))
        val textContext = messages.imageToolContextFor(Model())
        assertFalse(textContext.single().getTools().single().output.any { it is UIMessagePart.Image })
        assertTrue(messages.single().getTools().single().output.contains(image))
        val visionContext = messages.imageToolContextFor(Model(inputModalities = listOf(Modality.TEXT, Modality.IMAGE)))
        assertFalse(visionContext.single().getTools().single().output.any { it is UIMessagePart.Image })
    }

    @Test fun `invalid proposals and dimensions are rejected before submission`() {
        assertTrue(runCatching { imageToolProposedPrompt("""{"prompt":123}""") }.isFailure)
        assertTrue(runCatching { imageToolProposedPrompt("""{"prompt":" "}""") }.isFailure)
        assertTrue(runCatching { imageToolProposedPrompt("""{"prompt":"bird","count":4}""") }.isFailure)
        assertTrue(runCatching { imageToolProposedPrompt("""{"prompt":"bird","model":"other","size":"2048x2048"}""") }.isFailure)
        val constraints = ImageGenerationConstraints(true, false, false, maxOutputImages = 10, sequentialImageMax = 15,
            customSizeMultiple = 16, customSizeMinPixels = 512L * 512, customSizeMaxPixels = 2048L * 2048)
        assertEquals(4, constraints.forChatImageTool().maxOutputImages)
        assertEquals(4, constraints.forChatImageTool().sequentialImageMax)
        assertTrue(validImageToolSize("1024x1024", constraints))
        assertFalse(validImageToolSize("0x1024", constraints))
        assertFalse(validImageToolSize("1025x1024", constraints))
        assertFalse(validImageToolSize("9000000000x2", constraints))
    }

    @Test fun `inspection is opt in and disappears from text only wire context`() {
        val image = UIMessagePart.Image("file:///generated.png")
        val output = listOf(UIMessagePart.Text("saved"), image)
        val generated = UIMessagePart.Tool("generate", CHAT_IMAGE_TOOL_NAME, "{}", output = output)
        val inspected = UIMessagePart.Tool("inspect", INSPECT_GENERATED_IMAGE_TOOL_NAME, "{}", output = output)
        val message = UIMessage.assistant("").copy(parts = listOf(generated, inspected))
        val vision = Model(inputModalities = listOf(Modality.TEXT, Modality.IMAGE))
        val wire = listOf(message).imageToolContextFor(vision).single().getTools()
        assertFalse(wire[0].output.contains(image))
        assertTrue(wire[1].output.contains(image))
        assertFalse(listOf(message).imageToolContextFor(Model()).single().getTools().any { it.output.contains(image) })
        assertTrue(message.getTools().all { it.output.contains(image) })
    }

    @Test fun `wire context suggests inspection only when that interface is actually registered`() {
        val tool = UIMessagePart.Tool("generate", CHAT_IMAGE_TOOL_NAME, "{}", output = listOf(UIMessagePart.Image("file:///saved.png")))
        val history = listOf(UIMessage.assistant("").copy(parts = listOf(tool)))
        val vision = Model(inputModalities = listOf(Modality.TEXT, Modality.IMAGE))
        fun hint(model: Model, enabled: Boolean) = (history.imageToolContextFor(model, enabled)
            .single().getTools().single().output.single() as UIMessagePart.Text).text
        assertFalse(hint(vision, false).contains(INSPECT_GENERATED_IMAGE_TOOL_NAME))
        assertTrue(hint(vision, true).contains(INSPECT_GENERATED_IMAGE_TOOL_NAME))
        assertFalse(hint(Model(), true).contains(INSPECT_GENERATED_IMAGE_TOOL_NAME))
        assertTrue(history.single().getTools().single().output.single() is UIMessagePart.Image)
    }

    @Test fun `failed text continuation retains image result without reexecuting the tool`() {
        val image = UIMessagePart.Image("file:///saved.png")
        val tool = UIMessagePart.Tool("generate", CHAT_IMAGE_TOOL_NAME, """{"prompt":"bird"}""",
            approvalState = me.rerere.ai.ui.ToolApprovalState.Approved,
            output = listOf(UIMessagePart.Text("saved"), image))
        val message = UIMessage.assistant("").copy(parts = listOf(tool))
            .markInterruptedToolsForContinuation()
        assertTrue(message.canContinueInterruptedResponse())
        assertTrue(message.interrupted)
        assertFalse(message.getTools().single().canResumeExecution)
        val continuation = buildInterruptedStreamContinuation(emptyList(), listOf(message))!!
        assertEquals(tool, continuation.last().getTools().single())
        assertFalse(continuation.imageToolContextFor(Model()).last().getTools().single().output.any { it is UIMessagePart.Image })
    }

    @Test fun `automatic mode has no model writable parameter fields and defaults off`() {
        assertFalse(me.rerere.rikkahub.data.model.Assistant().autoApproveImageGeneration)
        val schema = chatImageToolDefinition(true).parameters() as me.rerere.ai.core.InputSchema.Obj
        assertEquals(setOf("prompt"), schema.properties.keys)
        assertEquals(listOf("prompt"), schema.required)
        val legacy = ChatImageToolRequest("m", "p", "model", "provider", "hash", ImageGenerationRequestState())
        assertFalse(JsonInstant.decodeFromString<ChatImageToolRequest>(JsonInstant.encodeToString(legacy)).automatic)
        val automatic = legacy.copy(automatic = true)
        assertEquals(automatic, JsonInstant.decodeFromString<ChatImageToolRequest>(JsonInstant.encodeToString(automatic)))
    }

    @Test fun `inspection refuses other conversations branches and altered calls`() {
        val conversationId = Uuid.random()
        val tool = UIMessagePart.Tool("call", CHAT_IMAGE_TOOL_NAME, """{"prompt":"bird"}""")
        val message = UIMessage.assistant("").copy(parts = listOf(tool))
        val task = ChatImageToolTaskEntity("task", conversationId.toString(), message.id.toString(), "source",
            tool.toolCallId, imageToolHash(tool.input), "original", "bird", "{}", createdAt = 0, updatedAt = 0)
        validateImageInspection(task, conversationId, listOf(message))
        assertTrue(runCatching { validateImageInspection(task, Uuid.random(), listOf(message)) }.isFailure)
        assertTrue(runCatching { validateImageInspection(task, conversationId, emptyList()) }.isFailure)
        assertTrue(runCatching { validateImageInspection(task, conversationId, listOf(message.copy(parts = listOf(tool.copy(input = "{}"))))) }.isFailure)
    }
}
