package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.ImageGenerationConstraints
import me.rerere.rikkahub.data.model.ImageGenerationRequestState
import me.rerere.rikkahub.utils.JsonInstant
import java.security.MessageDigest
import java.util.UUID

const val CHAT_IMAGE_TOOL_NAME = "generate_image"
const val INSPECT_GENERATED_IMAGE_TOOL_NAME = "inspect_generated_image"
const val CHAT_IMAGE_TOOL_TASK_KEY = "image_generation_task_id"
const val CHAT_IMAGE_TOOL_MAX_IMAGES = 4
const val CHAT_IMAGE_TOOL_APPROVAL_TTL = 60 * 60 * 1000L

internal fun imageToolPendingMetadata(original: JsonObject?, taskId: String) =
    JsonObject(original.orEmpty() + (CHAT_IMAGE_TOOL_TASK_KEY to JsonPrimitive(taskId)))

@Serializable
data class ChatImageToolRequest(
    val modelId: String,
    val providerId: String,
    val modelName: String,
    val providerName: String,
    val routeHash: String,
    val options: ImageGenerationRequestState,
    val automatic: Boolean = false,
)

fun imageToolTaskId(conversationId: String, messageId: String, callId: String): String =
    UUID.nameUUIDFromBytes("$conversationId\u0000$messageId\u0000$callId".toByteArray(Charsets.UTF_8)).toString()

fun imageToolHash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

fun imageToolProposedPrompt(input: String): String {
    val arguments = JsonInstant.parseToJsonElement(input).jsonObject
    require(arguments.keys == setOf("prompt")) { "模型只能提供提示词；模型、尺寸、数量和高级参数必须由用户设置" }
    val value = arguments["prompt"]?.jsonPrimitive
        ?.takeIf { it.isString }?.contentOrNull.orEmpty().trim()
    require(value.isNotBlank() && value.length <= 12_000) { "图片提示词须为 1–12000 个字符" }
    return value
}

internal fun validImageToolSize(value: String, constraints: ImageGenerationConstraints): Boolean {
    if (!constraints.supportsSize || value == "auto" || value in constraints.supportedSizes.orEmpty()) return true
    if (!constraints.supportsCustomSize) return false
    val match = Regex("^(\\d+)[xX×](\\d+)$").matchEntire(value.trim()) ?: return false
    val width = match.groupValues[1].toIntOrNull() ?: return false
    val height = match.groupValues[2].toIntOrNull() ?: return false
    if (width <= 0 || height <= 0) return false
    constraints.customSizeMultiple?.let { if (it <= 0 || width % it != 0 || height % it != 0) return false }
    constraints.customSizeMaxDimension?.let { if (width > it || height > it) return false }
    val pixels = width.toLong() * height
    constraints.customSizeMinPixels?.let { if (pixels < it) return false }
    constraints.customSizeMaxPixels?.let { if (pixels > it) return false }
    constraints.customSizeMaxAspectRatio?.let { if (maxOf(width, height).toLong() > minOf(width, height).toLong() * it) return false }
    return true
}

fun ImageGenerationConstraints.forChatImageTool() = copy(
    maxOutputImages = maxOutputImages.coerceIn(1, CHAT_IMAGE_TOOL_MAX_IMAGES),
    sequentialImageMax = sequentialImageMax?.coerceAtMost(CHAT_IMAGE_TOOL_MAX_IMAGES),
)

fun chatImageToolDefinition(automatic: Boolean = false, inspectionAvailable: Boolean = false) = Tool(
    name = CHAT_IMAGE_TOOL_NAME,
    description = (if (automatic) "Generate images using the user's configured image model and manual settings when useful for the current request. Autonomous invocation is enabled. "
        else "Generate images using the user's configured image model and manual settings; the app requires confirmation. ") +
        "Supply only a visual prompt. The user controls model, count, size and advanced parameters; ask_user cannot change them. Clarify ambiguous intent with ask_user first, wait for its answer, and never batch both calls. Rejection is not permission. At most one request per user turn; never automatically retry failed/uncertain generation. Wait for the result and continue answering; images are displayed by the app. Exclude unrelated private chat and system prompts. " +
        (if (inspectionAvailable) "Optionally call inspect_generated_image with the returned task_id before describing visual details."
        else "The result is text-only for this model; do not claim to see image content."),
    parameters = {
        InputSchema.Obj(buildJsonObject {
            put("prompt", buildJsonObject {
                put("type", "string"); put("description", "Visual prompt; no model or generation parameters")
                put("minLength", 1); put("maxLength", 12_000)
            })
        }, required = listOf("prompt"))
    },
    needsApproval = { true },
    execute = { error("Image generation requires the app's task-bound confirmation") },
)

/** Only saved, conversation-owned task IDs are accepted, never a model-supplied URL/path. */
fun inspectGeneratedImageTool(execute: suspend (String) -> List<me.rerere.ai.ui.UIMessagePart>) = Tool(
    name = INSPECT_GENERATED_IMAGE_TOOL_NAME,
    description = "Optionally view the saved images from generate_image when visual inspection is useful. Pass its task_id. This does not generate again or change image parameters. Only images in the current conversation branch are accessible.",
    parameters = { InputSchema.Obj(buildJsonObject {
        put("task_id", buildJsonObject { put("type", "string") })
    }, required = listOf("task_id")) },
    execute = { args ->
        require(args.jsonObject.keys == setOf("task_id")) { "Only task_id is accepted" }
        execute(requireNotNull(args.jsonObject["task_id"]?.jsonPrimitive?.takeIf { it.isString }?.contentOrNull))
    },
)
