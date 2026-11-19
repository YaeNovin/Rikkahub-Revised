package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.render.RendererCatalog
import me.rerere.rikkahub.data.render.RendererSpec
import me.rerere.rikkahub.data.render.compileDeclarativeRenderSpec
import me.rerere.rikkahub.data.render.isDeclarativeRenderer

internal const val LIST_RENDERERS_TOOL_NAME = "list_renderers"
internal const val RENDER_CONTENT_TOOL_NAME = "render_content"
internal data class RenderRequest(val renderer: RendererSpec, val source: String, val language: String)

internal fun parseRenderRequest(input: JsonElement): RenderRequest {
    val fields = input as? JsonObject ?: error("Expected an object")
    fun text(name: String): String? = (fields[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val renderer = RendererCatalog.find(text("renderer") ?: error("renderer must be a string"))
        ?: error("Unknown renderer; call list_renderers")
    val source = text("source") ?: error("source must be a string")
    require(source.isNotBlank()) { "source must not be blank" }
    require(source.length <= renderer.maxChars) { "Source exceeds ${renderer.maxChars} characters; split the content" }
    if (isDeclarativeRenderer(renderer.id)) compileDeclarativeRenderSpec(renderer.id, source)
    val language = text("language") ?: "plaintext"
    require(language.length <= 64 && language.all { it.isLetterOrDigit() || it in "_+-.#" }) { "Invalid code language" }
    return RenderRequest(renderer, source, language)
}

internal fun List<Tool>.withRenderingTools(enabled: Boolean): List<Tool> =
    filterNot { it.name in setOf(LIST_RENDERERS_TOOL_NAME, RENDER_CONTENT_TOOL_NAME) } +
        if (enabled) createRenderingTools() else emptyList()

internal fun createRenderingTools(): List<Tool> = listOf(
    Tool(
        name = LIST_RENDERERS_TOOL_NAME,
        description = "List the Android chat's available renderers. Pass renderer for its format, aliases, limits and example. HTML is manual static preview only. Does not change settings or execute source.",
        parameters = { InputSchema.Obj(buildJsonObject {
            put("renderer", buildJsonObject { put("type", "string"); put("description", "Optional renderer id/alias; omit for compact catalog.") })
        }) },
        systemPrompt = { _, messages ->
            val stale = messages.flatMap { it.getTools() }.filter { it.toolName == LIST_RENDERERS_TOOL_NAME && it.isExecuted }
                .any { tool -> tool.output.filterIsInstance<UIMessagePart.Text>().none { text ->
                    runCatching { Json.parseToJsonElement(text.text).jsonObject["catalog_version"]?.jsonPrimitive?.intOrNull == RendererCatalog.version }.getOrDefault(false)
                } }
            if (stale) "The current renderer catalog version is ${RendererCatalog.version}. Older list_renderers results may omit supported renderers; query list_renderers again before claiming a renderer is unavailable." else ""
        },
        execute = { input ->
            val field = (input as? JsonObject)?.get("renderer")
            val name = (field as? JsonPrimitive)?.takeIf { it.isString }?.content
            val spec = name?.let(RendererCatalog::find)
            val payload = if (field != null && spec == null) {
                buildJsonObject { put("status", "error"); put("error", "Unknown renderer; omit renderer to list the catalog") }
            } else buildJsonObject {
                put("platform", "android_chat")
                put("catalog_version", RendererCatalog.version)
                put("render_tool", RENDER_CONTENT_TOOL_NAME)
                put("renderers", buildJsonArray {
                    (spec?.let(::listOf) ?: RendererCatalog.entries).forEach { renderer -> add(buildJsonObject {
                        put("id", renderer.id); put("name", renderer.title)
                        put("format", renderer.format); put("automatic_preview", renderer.automatic)
                        if (spec != null) {
                            put("aliases", buildJsonArray { renderer.aliases.forEach(::add) })
                            put("max_source_chars", renderer.maxChars)
                            put("example", renderer.example); put("notes", renderer.notes)
                        }
                    }) }
                })
            }
            listOf(UIMessagePart.Text(payload.toString()))
        },
    ),
    Tool(
        name = RENDER_CONTENT_TOOL_NAME,
        description = "Submit a visual/text artifact for Android chat display using an existing renderer. Call list_renderers for formats. Do not wrap source in code fences. This is not code execution or image generation; acceptance is not proof of successful device rendering. HTML stays source-only until the user opens a static preview.",
        parameters = { InputSchema.Obj(buildJsonObject {
            put("renderer", buildJsonObject { put("type", "string"); put("description", "Renderer id or alias from list_renderers.") })
            put("source", buildJsonObject { put("type", "string"); put("description", "Raw renderer source, within the listed length limit.") })
            put("language", buildJsonObject { put("type", "string"); put("description", "Syntax language for the code renderer only; default plaintext.") })
        }, required = listOf("renderer", "source")) },
        execute = { input ->
            val request = runCatching { parseRenderRequest(input) }
            listOf(UIMessagePart.Text(buildJsonObject {
                request.fold(onSuccess = { value ->
                    put("status", "accepted"); put("renderer", value.renderer.id)
                    put("display", if (value.renderer.automatic) "chat_preview" else "manual_static_preview")
                    put("source_chars", value.source.length)
                    put("runtime_verified", false)
                }, onFailure = { error -> put("status", "error"); put("error", error.message ?: "Invalid renderer input") })
            }.toString()))
        },
    ),
)
