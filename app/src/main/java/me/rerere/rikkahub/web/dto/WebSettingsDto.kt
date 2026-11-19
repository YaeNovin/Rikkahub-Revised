package me.rerere.rikkahub.web.dto

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.utils.JsonInstant

/**
 * Browser settings are a projection, never the persisted Settings object. Every
 * nested object has its own allowlist so newly added credentials stay server-side.
 * Projecting serialized values preserves UUID, enum and polymorphic wire formats.
 */
fun Settings.toWebSettingsDto(): JsonObject = projectWebSettings(
    JsonInstant.encodeToJsonElement(this).jsonObject
)

internal fun projectWebSettings(source: JsonObject): JsonObject = source.select(
    "dynamicColor", "themeId", "developerMode", "favoriteModels", "chatModelId",
    "enableSuggestion", "assistantId", "searchServiceSelected", "webServerJwtEnabled",
).withObjects(source, "displaySetting") { display ->
    display.select(
        "userNickname", "showUserAvatar", "showModelIcon", "showModelName", "showTokenUsage",
        "showThinkingContent", "autoCloseThinking", "codeBlockAutoWrap", "codeBlockAutoCollapse",
        "showLineNumbers", "sendOnEnter", "enableAutoScroll", "fontSizeRatio",
        "pasteLongTextAsFile", "pasteLongTextThreshold",
    ).withObjects(display, "userAvatar", ::avatar)
}.withArray(source, "providers") { provider ->
    provider.select("id", "enabled", "name").withArray(provider, "models") { model ->
        model.select(
            "id", "modelId", "displayName", "type", "inputModalities", "outputModalities", "abilities",
        ).withArray(model, "tools") { it.select("type") }
    }
}.withArray(source, "assistants") { assistant ->
    assistant.select(
        "id", "chatModelId", "reasoningLevel", "enableWebSearch", "mcpServers",
        "modeInjectionIds", "lorebookIds", "allowConversationPromptInjection",
        "allowConversationSystemPrompt", "name", "useAssistantAvatar", "tags", "quickMessageIds",
    ).withObjects(assistant, "avatar", ::avatar)
}.withArray(source, "assistantTags") { it.select("id", "name") }
    .withArray(source, "modeInjections") { it.select("id", "name", "enabled") }
    .withArray(source, "lorebooks") { it.select("id", "name", "description", "enabled") }
    .withArray(source, "quickMessages") { it.select("id", "title", "content") }
    .withArray(source, "searchServices") { it.select("id", "type") }
    .withArray(source, "mcpServers") { server ->
        server.select("id", "type").withObjects(server, "commonOptions") { options ->
            options.select("enable", "name").withArray(options, "tools") {
                it.select("enable", "name", "description", "needsApproval")
            }
        }
    }

private fun avatar(source: JsonObject) = source.select("type", "content", "url")

private fun JsonObject.select(vararg keys: String): JsonObject =
    JsonObject(keys.mapNotNull { key -> get(key)?.let { key to it } }.toMap())

private fun JsonObject.withObjects(
    source: JsonObject,
    key: String,
    project: (JsonObject) -> JsonObject,
): JsonObject = JsonObject(toMutableMap().apply {
    (source[key] as? JsonObject)?.let { put(key, project(it)) }
})

private fun JsonObject.withArray(
    source: JsonObject,
    key: String,
    project: (JsonObject) -> JsonObject,
): JsonObject = JsonObject(toMutableMap().apply {
    val items = source[key] as? JsonArray ?: return@apply
    put(key, JsonArray(items.mapNotNull { (it as? JsonObject)?.let(project) }))
})
