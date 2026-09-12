package me.rerere.ai.ui

import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema

/** Shared by tool discovery, provider serialization and the runtime validator. */
object AskUserContract {
    const val VERSION = 2
    val presentations = listOf("auto", "chips", "list", "cards")
    val questionFields = setOf("id", "question", "description", "placeholder", "default", "default_values", "options",
        "option_details", "selection_type", "presentation", "required", "min_length", "max_length", "min_items",
        "max_items", "min_value", "max_value", "step", "unit", "danger", "timeout_seconds", "visible_if")
    val description = """
        Ask the user a compact interactive form and wait for their answers. Always pass a questions array with unique id and question fields.
        selection_type chooses text, single, multi, slider, rating, confirm, date or time.
        Only text allows free typing; single/multi accept configured option values only. presentation=auto/chips/list/cards controls text suggestions or single/multi options.
        option_details attaches a label, badge, text/emoji icon and HTTPS image to an option value; returned answers use value, not label.
        Slider requires min_value/max_value and snaps to step; rating defaults to 1..5 step 1 and supports at most 10 values.
        Use string default values (including numeric strings), and default_values for multi-select. Confirmations require an explicit click; legacy defaults are not user consent.
        visible_if accepts a predicate or an all/any/not group. Use only existing question IDs; cycles are rejected. Hidden questions are not submitted.
        A timed confirm starts when displayed; timeout means false, not permission. Do not use ask_user as a substitute for the execution tool's own approval.
        The entire request expires 30 minutes after creation; confirmation timers cannot extend that limit.
        Returns status=answered with typed answers and question_types; cancelled/expired/denied/invalid_request are not approval.
        Use get_session_capabilities(include_ask_user_details=true) for limits, field rules and valid examples. Prefer 1-5 questions on phones.
    """.trimIndent().replace("\n", " ")

    private fun field(type: String, description: String, block: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        put("type", type); put("description", description); block()
    }
    private fun strings(values: List<String>) = JsonArray(values.map(::JsonPrimitive))
    private fun conditionSchema(group: Boolean): JsonObject = field("object", "Exactly one predicate (question_id/operator/value or values) or one all/any/not group. Numeric comparisons accept numeric strings.") {
        put("properties", buildJsonObject {
            put("question_id", field("string", "ID of a question in this form; no self-reference or cycles."))
            put("operator", field("string", "Defaults to equals. exists needs no value; in uses values.") {
                put("enum", strings(AskUserProtocol.ConditionOperator.entries.map { it.wireValue }))
            })
            put("value", field("string", "Comparison value; numbers and booleans as strings, e.g. 0.5 or true. Not used with exists/in."))
            put("values", field("array", "Values to match for operator=in; compare against stable option values.") {
                put("items", field("string", "One candidate value")); put("maxItems", AskUserProtocol.MAX_OPTIONS)
            })
            if (group) {
                put("root", field("string", "For complex logic, supply only root and nodes; root is a condition node ID."))
                put("nodes", field("array", "Flat condition graph (max 32 nodes, depth 8, 512 expanded nodes), avoiding recursive schemas. Predicate nodes use question_id/operator/value or values; group nodes use children IDs. No cycles or unused nodes.") {
                    put("maxItems", 32); put("minItems", 1)
                    put("items", field("object", "A named predicate or all/any/not group.") {
                        put("properties", buildJsonObject {
                            put("id", field("string", "Unique condition node ID"))
                            put("kind", field("string", "predicate, all, any or not") { put("enum", strings(listOf("predicate", "all", "any", "not"))) })
                            put("children", field("array", "Group child node IDs; not requires exactly one.") { put("items", field("string", "Node ID")) })
                            (conditionSchema(false)["properties"] as JsonObject).forEach { (key, value) -> put(key, value) }
                        }); put("required", strings(listOf("id", "kind")))
                    })
                })
                listOf("all", "any").forEach { key -> put(key, field("array", "${if (key == "all") "All" else "At least one"} of these predicates must match.") {
                    put("items", conditionSchema(false)); put("minItems", 1); put("maxItems", 32)
                }) }
                put("not", conditionSchema(false))
            }
        })
    }

    fun schema(): InputSchema.Obj = InputSchema.Obj(properties = buildJsonObject {
        put("questions", field("array", "1-${AskUserProtocol.MAX_QUESTIONS} questions; prefer 1-5 for mobile screens.") {
            put("minItems", 1); put("maxItems", AskUserProtocol.MAX_QUESTIONS)
            put("items", field("object", "Choose selection_type explicitly. Omit fields that do not apply to that type.") {
                put("properties", buildJsonObject {
                    put("id", field("string", "Unique nonblank ID, max ${AskUserProtocol.MAX_ID_LENGTH} characters.") { put("maxLength", AskUserProtocol.MAX_ID_LENGTH) })
                    put("question", field("string", "Question shown to the user; max ${AskUserProtocol.MAX_QUESTION_LENGTH} characters.") { put("maxLength", AskUserProtocol.MAX_QUESTION_LENGTH) })
                    put("selection_type", field("string", "text=input plus optional suggestions; single/multi=closed choices; slider=numeric range; rating=stars; confirm=yes/no; date=YYYY-MM-DD; time=HH:mm (device local time). Default text.") {
                        put("enum", strings(AskUserProtocol.SelectionType.entries.map { it.wireValue }))
                    })
                    put("presentation", field("string", "auto/chips/list/cards. Auto uses cards for images/badges, lists for >6 or long options, otherwise chips. Only text with suggestions, single or multi; numeric/date/confirm use auto. >8 options show search.") { put("enum", strings(presentations)) })
                    put("required", field("boolean", "Default true. Optional unanswered fields are omitted."))
                    put("description", field("string", "Supporting context, max ${AskUserProtocol.MAX_DESCRIPTION_LENGTH} characters."))
                    put("placeholder", field("string", "Text-input hint, max ${AskUserProtocol.MAX_PLACEHOLDER_LENGTH} characters."))
                    put("default", field("string", "Text/single/date/time default; numeric defaults as strings. Omit for confirm: a default is not user consent. For multi use default_values; comma-separated legacy strings are ambiguous."))
                    put("default_values", field("array", "Multi-select defaults, exact option values. Do not also supply default.") { put("items", field("string", "Option value")); put("maxItems", AskUserProtocol.MAX_OPTIONS) })
                    put("options", field("array", "Up to ${AskUserProtocol.MAX_OPTIONS} unique nonblank values, each up to ${AskUserProtocol.MAX_OPTION_LENGTH} characters. Required for single/multi; optional suggestions for text.") {
                        put("items", field("string", "Stable answer value")); put("maxItems", AskUserProtocol.MAX_OPTIONS)
                    })
                    put("option_details", field("array", "Rich option metadata, matching options by value. Can define the options when options is omitted.") {
                        put("maxItems", AskUserProtocol.MAX_OPTIONS)
                        put("items", field("object", "One option") {
                            put("properties", buildJsonObject {
                                put("value", field("string", "Unique stable answer value"))
                                put("label", field("string", "Display label; defaults to value"))
                                put("badge", field("string", "Short supplementary badge text"))
                                put("icon", field("string", "Literal text or emoji, e.g. ⭐. Not a library icon name."))
                                put("image", field("string", "Public HTTPS image URL, max 2048 characters; may need network access."))
                            }); put("required", strings(listOf("value")))
                        })
                    })
                    listOf("min_length", "max_length").forEach { put(it, field("integer", "Text only: ${if (it.startsWith("min")) "minimum" else "maximum"} characters.") {
                        put("minimum", 0); put("maximum", AskUserProtocol.MAX_TEXT_ANSWER_LENGTH)
                    }) }
                    listOf("min_items", "max_items").forEach { put(it, field("integer", "Multi only: selection count, bounded by options size; min must not exceed max.") {
                        put("minimum", 0); put("maximum", AskUserProtocol.MAX_OPTIONS)
                    }) }
                    listOf("min_value", "max_value").forEach { put(it, field("number", "Slider/rating only; finite within +/-1e12, min_value < max_value. Slider requires both; rating defaults 1..5.") {
                        put("minimum", -AskUserProtocol.MAX_NUMERIC_MAGNITUDE); put("maximum", AskUserProtocol.MAX_NUMERIC_MAGNITUDE)
                    }) }
                    put("step", field("number", "Positive step >=1e-9; <=range. Slider default range/100, at most 1e9 intervals. Rating default 1, at most 10 evenly spaced values.") { put("minimum", AskUserProtocol.MIN_NUMERIC_STEP) })
                    put("unit", field("string", "Slider/rating unit, e.g. %; max 64 characters."))
                    put("danger", field("boolean", "Confirm only, default false. Emphasize a consequential decision; danger implies a 30-second countdown unless timeout_seconds is provided."))
                    put("timeout_seconds", field("integer", "Confirm only, 1..3600 seconds after display, bounded by the entire request's 30-minute lifetime. Plain confirm has no countdown unless this or danger is set. Unanswered timeout becomes false.") { put("minimum", 1); put("maximum", 3600) })
                    put("visible_if", conditionSchema(true))
                }); put("required", strings(listOf("id", "question")))
            })
        })
    }, required = listOf("questions"))

    fun capabilities(details: Boolean): JsonObject = buildJsonObject {
        put("version", VERSION)
        put("selection_types", strings(AskUserProtocol.SelectionType.entries.map { it.wireValue }))
        put("presentations", strings(presentations))
        put("max_questions", AskUserProtocol.MAX_QUESTIONS); put("max_options", AskUserProtocol.MAX_OPTIONS)
        put("approval_timeout_seconds", AskUserProtocol.APPROVAL_TIMEOUT_MILLIS / 1000)
        put("drafts", "conversation-scoped and persisted; never sent to the model until submitted")
        put("conditions", "predicate or one all/any/not group; complex nesting uses root+nodes (max 32 nodes, depth 8); legacy nested groups remain supported; hidden answers excluded")
        if (details) {
            put("description", description)
            put("question_parameters", schema().properties["questions"]!!)
            put("examples", Json.parseToJsonElement("""[
              {"questions":[{"id":"style","question":"Choose style","selection_type":"single","presentation":"cards","options":["brief","full"],"option_details":[{"value":"brief","label":"Brief","icon":"⚡","badge":"Fast"}]}]},
              {"questions":[{"id":"score","question":"Score","selection_type":"rating","default":"4"},{"id":"ratio","question":"Ratio","selection_type":"slider","min_value":0,"max_value":1,"step":0.0001,"default":"0.1234"}]},
              {"questions":[{"id":"tags","question":"Topics","selection_type":"multi","options":["a,b","c"],"default_values":["a,b"]},{"id":"detail","question":"Details","visible_if":{"question_id":"tags","operator":"contains","value":"a,b"}}]},
              {"questions":[{"id":"mode","question":"Mode"},{"id":"detail","question":"Details","visible_if":{"root":"group","nodes":[{"id":"group","kind":"any","children":["a","b"]},{"id":"a","kind":"predicate","question_id":"mode","value":"work"},{"id":"b","kind":"predicate","question_id":"mode","value":"play"}]}}]}
            ]"""))
        }
    }

    fun answerResult(request: AskUserProtocol.Request, answer: String): String {
        val normalized = Json.parseToJsonElement(AskUserProtocol.validateAnswer(request, answer).getOrThrow()).jsonObject
        val answers = normalized.getValue("answers").jsonObject
        return buildJsonObject {
            put("status", "answered"); put("answers", answers)
            put("question_types", buildJsonObject { request.questions.filter { it.id in answers }.forEach { put(it.id, it.selectionType.wireValue) } })
            put("question_presentations", buildJsonObject { request.questions.filter { it.id in answers }.forEach { put(it.id, presentationFor(it)) } })
        }.toString()
    }

    fun presentationFor(question: AskUserProtocol.Question): String = when {
        question.selectionType !in setOf(AskUserProtocol.SelectionType.TEXT, AskUserProtocol.SelectionType.SINGLE, AskUserProtocol.SelectionType.MULTI) -> question.selectionType.wireValue
        question.options.isEmpty() -> "input"
        question.presentation != "auto" -> question.presentation
        question.optionDetails.any { it.imageUrl.isNotBlank() || it.badge.isNotBlank() } -> "cards"
        question.options.size > 6 || question.options.any { it.length > 28 } -> "list"
        else -> "chips"
    }
}
