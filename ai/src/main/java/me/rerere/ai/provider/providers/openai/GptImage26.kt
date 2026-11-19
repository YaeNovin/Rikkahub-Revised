package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.*
import me.rerere.ai.provider.GptImage26Options
import me.rerere.ai.provider.ImageGenerationConstraints

internal fun isGptImage26(modelId: String): Boolean =
    Regex("(?:^|[^a-z0-9])(?:gpt|tt)[^a-z0-9]*image[^a-z0-9]*2[._-]?6(?:-(?:flare|sunburst))?(?:$|[^a-z0-9])")
        .containsMatchIn(modelId.lowercase())

internal fun GptImage26Options.requestFields(constraints: ImageGenerationConstraints, format: String?): Map<String, JsonElement> = buildMap {
    if (!constraints.supportsGptImage26Options) return@buildMap
    version?.takeIf { constraints.supportedGptImage26Versions.isNotEmpty() }?.let {
        require(it in constraints.supportedGptImage26Versions) { "模型版本需要 flare 或 sunburst" }
        put("version", JsonPrimitive(it))
    }
    complexity?.let {
        require(it in setOf("low", "medium", "high")) { "图像规划复杂度需要 low、medium 或 high" }
        put("complexity", JsonPrimitive(it))
    }
    if (format == "gif") {
        frameCount?.let { require(it in 2..16) { "GIF 帧数需要 2–16" }; put("frame_count", JsonPrimitive(it)) }
        frameDurationMs?.let { require(it in 20..655350) { "GIF 每帧时长需要 20–655350 毫秒" }; put("frame_duration_ms", JsonPrimitive(it)) }
    }
    if (format == "svg") svgModel?.trim()?.takeIf(String::isNotEmpty)?.let { author ->
        put("svg", buildJsonObject {
            put("model", author)
            svgReasoningEffort?.let {
                require(it in setOf("none", "minimal", "low", "medium", "high", "xhigh", "max")) { "SVG 推理深度无效" }
                put("reasoning", buildJsonObject { put("effort", it) })
            }
        })
    }
}

/** Validate combinations after custom fields are merged, for generation and editing alike. */
internal fun normalizeGptImage26Request(body: JsonObject): JsonObject {
    fun stringField(owner: JsonObject, key: String): String? = owner[key]?.let {
        require(it is JsonPrimitive && it.isString) { "$key 需要字符串" }
        it.content
    }
    fun integerField(key: String): Int? = body[key]?.let {
        require(it is JsonPrimitive && !it.isString && it.intOrNull != null) { "$key 需要整数" }
        it.int
    }
    val format = stringField(body, "output_format") ?: "png"
    require(format in setOf("png", "jpeg", "webp", "gif", "svg")) { "图像输出格式无效" }
    val fields = body.toMutableMap()
    stringField(body, "complexity")?.let {
        require(it in setOf("low", "medium", "high")) { "图像规划复杂度无效" }
    }
    require(body["mask"] == null || body["markup"] == null) { "mask 与 markup 不能同时使用" }
    fields.remove("response_format")
    fields.remove("style")
    if (format in setOf("gif", "svg")) {
        listOf("stream", "partial_images", "output_compression").forEach(fields::remove)
    }
    if (format == "gif") {
        require(body["mask"] == null) { "GIF 输出不支持蒙版" }
        integerField("frame_count")?.let { require(it in 2..16) { "GIF 帧数需要 2–16" } }
        integerField("frame_duration_ms")?.let { require(it in 20..655350) { "GIF 每帧时长无效" } }
        val size = stringField(body, "size") ?: "auto"
        require(size in setOf("auto", "512x512")) { "GIF 请使用自动尺寸或 512x512" }
    } else {
        fields.remove("frame_count"); fields.remove("frame_duration_ms")
    }
    if (format == "svg") {
        fields["n"] = JsonPrimitive(1)
        fields.remove("prompt_rewriter")
        body["svg"]?.let { svg ->
            require(svg is JsonObject) { "svg 需要对象" }
            require(!stringField(svg, "model").isNullOrBlank()) { "SVG 绘制模型不能为空" }
            svg["reasoning"]?.let { reasoning ->
                require(reasoning is JsonObject) { "SVG reasoning 需要对象" }
                require(stringField(reasoning, "effort") in setOf("none", "minimal", "low", "medium", "high", "xhigh", "max")) {
                    "SVG 推理深度无效"
                }
            }
        }
    } else fields.remove("svg")
    if (format !in setOf("png", "webp", "svg") && (fields["background"] as? JsonPrimitive)?.contentOrNull == "transparent") {
        fields.remove("background")
    }
    return JsonObject(fields)
}
