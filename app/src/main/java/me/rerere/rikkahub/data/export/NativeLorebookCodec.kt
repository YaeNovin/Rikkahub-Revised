package me.rerere.rikkahub.data.export

import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.model.*
import kotlin.uuid.Uuid

/** Native exports must retain native positions and must never fall back to Tavern on errors. */
internal fun decodeLorebookDocument(text: String, fallbackName: String): Lorebook {
    val root = ExportSerializer.DefaultJson.parseToJsonElement(text) as? JsonObject
        ?: throw IllegalArgumentException("世界书文件根节点必须是 JSON 对象")
    if ("type" in root || "data" in root || "version" in root) {
        require((root["type"] as? JsonPrimitive)?.contentOrNull == "lorebook") { "文件不是原生世界书导出（type 应为 lorebook）" }
        val version = (root["version"] as? JsonPrimitive)?.intOrNull ?: if ("version" !in root) 1 else -1
        require(version == 1) { "不支持的世界书导出版本：$version，请更新软件或重新导出" }
        val data = root["data"] as? JsonObject ?: throw IllegalArgumentException("原生世界书缺少 data 对象")
        return decodeNativeLorebook(data)
    }
    val entries = root["entries"] as? JsonArray
    val looksNative = entries?.any { element -> (element as? JsonObject)?.let { "constantActive" in it || "injectDepth" in it || "keywords" in it } == true } == true
    if (looksNative) return decodeNativeLorebook(root)
    return decodeTavernLorebook(root, fallbackName)
}

internal fun decodeNativeLorebook(data: JsonObject): Lorebook {
    val rawEntries = data["entries"] as? JsonArray ?: throw IllegalArgumentException("原生世界书 entries 必须是数组")
    require(rawEntries.size <= 10000) { "世界书最多支持 10000 个条目" }
    val warnings = mutableListOf<String>()
    val entries = rawEntries.mapIndexed { index, raw ->
        val entry = raw as? JsonObject ?: throw IllegalArgumentException("第 ${index + 1} 个世界书条目必须是对象")
        val decoded = try {
            ExportSerializer.DefaultJson.decodeFromJsonElement<PromptInjection.RegexInjection>(JsonObject(entry - "id"))
        } catch (e: Exception) {
            throw IllegalArgumentException("第 ${index + 1} 个原生条目字段无效（请检查角色、注入位置、数字及扫描模式）：${e.message}", e)
        }
        decoded.validationErrors(true).forEach { warnings += "${decoded.name.ifBlank { "条目 ${index + 1}" }}：$it" }
        decoded.copy(id = Uuid.random(), scanDepth = decoded.scanDepth.coerceIn(0, 1000), injectDepth = decoded.injectDepth.coerceIn(1, 1000),
            triggerProbability = decoded.triggerProbability.coerceIn(0, 100), stickyTurns = decoded.stickyTurns.coerceIn(1, 10000),
            cooldownTurns = decoded.cooldownTurns.coerceIn(0, 10000), selectionWeight = decoded.selectionWeight.coerceIn(0, 10000))
    }
    val original = try {
        ExportSerializer.DefaultJson.decodeFromJsonElement<Lorebook>(JsonObject((data - "id" - "revisions") + ("entries" to JsonArray(emptyList()))))
    } catch (e: Exception) {
        throw IllegalArgumentException("原生世界书基本配置无效：${e.message}", e)
    }
    if (original.defaultScanDepth !in 0..1000) warnings += "本书默认扫描深度已调整至 0–1000 范围"
    if (original.tokenBudget !in 0..1000000) warnings += "本书 Token 预算已调整至 0–1000000 范围"
    return original.copy(id = Uuid.random(), entries = entries, revisions = emptyList(),
        defaultScanDepth = original.defaultScanDepth.coerceIn(0, 1000), tokenBudget = original.tokenBudget.coerceIn(0, 1000000),
        importWarnings = (original.importWarnings + warnings).distinct())
}
