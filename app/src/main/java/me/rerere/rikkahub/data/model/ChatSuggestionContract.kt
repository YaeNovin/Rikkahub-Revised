package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.*
import me.rerere.ai.ui.AskUserContract
import me.rerere.rikkahub.data.datastore.ChatSuggestionDisplayMode
import me.rerere.rikkahub.data.datastore.ChatSuggestionStyle
import me.rerere.rikkahub.data.datastore.Settings
import java.util.Locale

/** One contract for the background generator, session discovery and local output validation. */
internal object ChatSuggestionContract {
    const val MAX_DESCRIPTION = 120
    const val MAX_PAYLOAD = 500
    const val MAX_QUESTIONS = 5
    val fieldTypes = listOf("text", "single", "multi", "slider", "rating", "date", "time")
    val formActions = ChatSuggestionAction.entries.toSet() - setOf(
        ChatSuggestionAction.COPY_TEXT, ChatSuggestionAction.SAVE_QUICK_MESSAGE,
    )

    fun wire(value: Enum<*>) = value.name.lowercase(Locale.ROOT)
    private fun strings(values: Iterable<String>) = JsonArray(values.map(::JsonPrimitive))
    private fun field(type: String, description: String, maxLength: Int? = null) = buildJsonObject {
        put("type", type); put("description", description)
        maxLength?.let { put("maxLength", it) }
    }

    // Reuse the actual ask_user validator's definitions, with the draft-only restrictions.
    private val questionSchema by lazy {
        val original = AskUserContract.schema().properties["questions"]!!.jsonObject
        val item = original["items"]!!.jsonObject
        val fields = item["properties"]!!.jsonObject.toMutableMap().apply {
            remove("danger"); remove("timeout_seconds")
            put("selection_type", buildJsonObject {
                put("type", "string"); put("enum", strings(fieldTypes))
                put("description", "Local draft input type; text is free typing. Confirm is not supported here.")
            })
            put("presentation", buildJsonObject {
                put("type", "string"); put("enum", strings(AskUserContract.presentations))
                put("description", "For single/multi choices: auto, chips, list or cards; text uses a plain input.")
            })
            put("options", JsonObject(getValue("options").jsonObject + ("description" to JsonPrimitive(
                "Single/multi choices: unique nonblank stable option values. Text uses a plain input, without suggestion chips.",
            ))))
        }
        JsonObject(original + mapOf(
            "description" to JsonPrimitive("1-$MAX_QUESTIONS local draft questions with unique IDs; not a tool call."),
            "maxItems" to JsonPrimitive(MAX_QUESTIONS),
            "items" to JsonObject(item + ("properties" to JsonObject(fields))),
        ))
    }

    fun responseSchema(config: ChatSuggestionConfig, actions: Set<ChatSuggestionAction>, count: Int): JsonObject {
        val bounded = config.bounded()
        return buildJsonObject {
            put("type", "object")
            put("required", strings(listOf("suggestions")))
            put("properties", buildJsonObject {
                put("suggestions", buildJsonObject {
                    put("type", "array"); put("minItems", 0); put("maxItems", count.coerceIn(0, bounded.count))
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("required", strings(listOf("text", "description", "category", "action", "payload")))
                        put("properties", buildJsonObject {
                            put("text", field("string", "Short visible card title, not the full request.", bounded.maxLength))
                            put("description", field("string", "Optional concise explanation in the user's language; use empty string when redundant.", MAX_DESCRIPTION))
                            put("payload", field("string", "Complete user request/query to insert. Never a tool-arguments object or a claim that work has run.", MAX_PAYLOAD))
                            put("category", buildJsonObject {
                                put("type", "string"); put("enum", strings(categories(bounded.style)))
                            })
                            put("action", buildJsonObject { put("type", "string"); put("enum", strings(actions.map(::wire))) })
                            if (bounded.options.parameterForms) put("parameters", buildJsonObject {
                                put("type", "object"); put("required", strings(listOf("questions")))
                                put("description", "Omit unless payload uses {{question_id}}. Only actions listed in parameter_form_actions allow this.")
                                put("properties", buildJsonObject { put("questions", questionSchema) })
                            })
                        })
                    })
                })
            })
        }
    }

    fun categories(style: ChatSuggestionStyle): List<String> = ChatSuggestionCategory.entries
        .filter { style == ChatSuggestionStyle.ROLEPLAY || it in listOf(ChatSuggestionCategory.FOLLOW_UP, ChatSuggestionCategory.ACTION, ChatSuggestionCategory.DIRECTION) }
        .map(::wire)

    fun describe(config: ChatSuggestionConfig, actions: Set<ChatSuggestionAction>, count: Int = config.count, details: Boolean): JsonObject {
        val bounded = config.bounded()
        return buildJsonObject {
            put("version", 1)
            put("execution", "Suggestions are generated by a separate background request. Selecting a draft does not send a message or execute a tool. This capability query cannot create cards.")
            put("settings", buildJsonObject {
                put("count", count.coerceIn(0, bounded.count)); put("max_text_characters", bounded.maxLength)
                put("max_description_characters", MAX_DESCRIPTION); put("max_payload_characters", MAX_PAYLOAD)
                put("style", wire(bounded.style)); put("display_mode", wire(bounded.displayMode))
                put("trigger", wire(bounded.options.trigger)); put("interval_seconds", bounded.options.intervalSeconds)
                put("context_budget_tokens", bounded.options.contextBudget)
                put("summary_requested", bounded.options.includeSummary); put("memory_requested", bounded.options.includeMemory)
                put("balanced_categories", bounded.options.balancedCategories)
                put("preview_before_insert", bounded.options.previewBeforeInsert); put("insert_mode", wire(bounded.insertMode))
                put("parameter_forms", bounded.options.parameterForms); put("image_suggestions", bounded.options.imageSuggestions)
            })
            put("display_guidance", when (bounded.displayMode) {
                ChatSuggestionDisplayMode.COMPACT -> "One visible text line; description is hidden. Put essential meaning early in text and the full request in payload."
                ChatSuggestionDisplayMode.TWO_ROW -> "Two rows of chips; each title has one line, descriptions are hidden. Use short distinct titles."
                ChatSuggestionDisplayMode.RICH -> "Mobile cards: text and description each have at most two visible lines; longer content is ellipsized."
                ChatSuggestionDisplayMode.AUTO -> "One suggestion uses a single-line chip. Multiple suggestions use rich cards when description or non-insert action is present, otherwise two rows of chips."
            })
            put("selection_guidance", if (bounded.options.balancedCategories)
                "Vary the allowed categories when useful; roleplay directions must stay within the supplied scene, without inventing hidden setting facts."
                else "Rank suggestions by usefulness; do not invent hidden setting facts.")
            put("categories", strings(categories(bounded.style)))
            put("actions", buildJsonObject { actions.sortedBy(::wire).forEach { put(wire(it), actionBehavior(it)) } })
            put("parameter_form_actions", strings(if (bounded.options.parameterForms) actions.intersect(formActions).map(::wire) else emptyList()))
            if (bounded.options.parameterForms) {
                put("form_rules", "At most $MAX_QUESTIONS questions. IDs must match payload {{question_id}} placeholders. single/multi use exact option values; numeric defaults are strings; date is YYYY-MM-DD, time HH:mm. visible_if references this form only; hidden values insert as empty. No confirm, danger or countdown fields. Never imply that filling a draft grants tool approval.")
                put("field_types", strings(fieldTypes))
            }
            if (details) {
                put("response_schema", responseSchema(bounded, actions, count))
                if (bounded.options.parameterForms && ChatSuggestionAction.INSERT_TEXT in actions) {
                    put("example", Json.parseToJsonElement("""{"suggestions":[{"text":"Compare options","description":"Choose a focus","category":"follow_up","action":"insert_text","payload":"Compare the options for {{focus}}.","parameters":{"questions":[{"id":"focus","question":"Which aspect matters?","selection_type":"single","presentation":"chips","options":["cost","performance"]}]}}]}"""))
                }
            }
        }
    }

    private fun actionBehavior(action: ChatSuggestionAction): String = when (action) {
        ChatSuggestionAction.INSERT_TEXT -> "Preview/insert payload into the chat draft; user sends it manually."
        ChatSuggestionAction.COPY_TEXT -> "Copy payload to clipboard on selection; no parameter form."
        ChatSuggestionAction.SAVE_QUICK_MESSAGE -> "Save as a quick message on selection; no parameter form."
        ChatSuggestionAction.CREATE_BRANCH -> "Create a branch from the source message and prepare a draft after user selection."
        ChatSuggestionAction.IMAGE_DRAFT -> "Prepare an image prompt and open image settings; user explicitly sends to the image model."
        ChatSuggestionAction.SEARCH_WEB -> "Prepare a web-search request in the draft; search runs only after the user sends it."
        ChatSuggestionAction.SEARCH_KNOWLEDGE -> "Prepare a knowledge-base search query; does not directly search."
        ChatSuggestionAction.SEARCH_MEMORY -> "Prepare a memory-retrieval request; does not directly retrieve memory."
        ChatSuggestionAction.SEARCH_CONVERSATIONS -> "Prepare a past-conversation search request; does not directly search."
        ChatSuggestionAction.ASK_USER -> "Prepare a request to ask the user interactively in a subsequent chat turn; local parameters are a separate draft form."
        ChatSuggestionAction.WORKSPACE -> "Prepare a workspace task; no file or command execution until a later authorized tool call."
        ChatSuggestionAction.USE_SKILL -> "Prepare a request to use an available skill; do not invent a skill name."
        ChatSuggestionAction.MCP -> "Prepare an MCP task; actual tools and approval are resolved in a later chat turn."
    }
}

internal fun Conversation.suggestionCapabilities(settings: Settings, details: Boolean, knowledgeAvailable: Boolean): JsonObject {
    val config = suggestionConfig(settings)
    val actions = availableSuggestionActions(settings).filterTo(linkedSetOf()) {
        it != ChatSuggestionAction.SEARCH_KNOWLEDGE || knowledgeAvailable
    }
    return buildJsonObject {
        put("enabled", config.options.trigger != SuggestionTrigger.DISABLED && !suggestionSession.paused)
        put("paused", suggestionSession.paused); put("collapsed", suggestionSession.collapsed)
        put("scope", if (suggestionSession.target == null) "latest_response" else "selected_message")
        ChatSuggestionContract.describe(config, actions, details = details).forEach { (key, value) -> put(key, value) }
    }
}
