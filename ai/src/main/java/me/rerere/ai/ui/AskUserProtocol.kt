package me.rerere.ai.ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.util.json
import java.util.Locale

/**
 * Canonical request and answer validation for the interactive ask_user tool.
 *
 * The validator is intentionally provider independent. Provider schemas are
 * hints only; this is the final guard before a request is persisted as Pending
 * or an answer is accepted for execution.
 */
object AskUserProtocol {
    const val TOOL_NAME = "ask_user"
    const val MAX_QUESTIONS = 32
    const val MAX_OPTIONS = 64
    const val MAX_ID_LENGTH = 128
    const val MAX_QUESTION_LENGTH = 4096
    const val MAX_OPTION_LENGTH = 512
    const val MAX_PLACEHOLDER_LENGTH = 512
    const val MAX_DESCRIPTION_LENGTH = 4096
    const val MAX_ANSWER_LENGTH = 64 * 1024
    const val MAX_TEXT_ANSWER_LENGTH = 16 * 1024
    const val MAX_RATING_STEPS = 10
    const val MAX_NUMERIC_MAGNITUDE = 1e12
    const val MIN_NUMERIC_STEP = 1e-9
    const val PENDING_AT_METADATA_KEY = "ask_user_pending_at"
    const val CONFIRM_DEADLINE_METADATA_KEY = "ask_user_confirm_deadline_at"
    const val DEFAULT_CONFIRM_TIMEOUT_SECONDS = 30
    const val APPROVAL_TIMEOUT_MILLIS = 30 * 60 * 1000L

    enum class SelectionType(val wireValue: String) {
        TEXT("text"),
        SINGLE("single"),
        MULTI("multi"),
        SLIDER("slider"),
        RATING("rating"),
        CONFIRM("confirm"),
        DATE("date"),
        TIME("time");

        companion object {
            fun fromWire(value: String?): SelectionType = when (value?.lowercase(Locale.ROOT)) {
                null, "" -> TEXT
                "text" -> TEXT
                "single" -> SINGLE
                "multi" -> MULTI
                "slider" -> SLIDER
                "rating" -> RATING
                "confirm" -> CONFIRM
                "date" -> DATE
                "time" -> TIME
                else -> error("Unsupported ask_user selection_type: $value")
            }
        }
    }

    enum class ConditionOperator(val wireValue: String) {
        EXISTS("exists"),
        EQUALS("equals"),
        NOT_EQUALS("not_equals"),
        CONTAINS("contains"),
        IN("in"),
        GREATER_THAN("gt"),
        GREATER_OR_EQUAL("gte"),
        LESS_THAN("lt"),
        LESS_OR_EQUAL("lte");

        companion object {
            fun fromWire(value: String?): ConditionOperator = when (
                value?.lowercase(Locale.ROOT).orEmpty()
            ) {
                "", "equals", "eq" -> EQUALS
                "exists" -> EXISTS
                "not_equals", "neq" -> NOT_EQUALS
                "contains" -> CONTAINS
                "in" -> IN
                "gt", "greater_than" -> GREATER_THAN
                "gte", "greater_or_equal" -> GREATER_OR_EQUAL
                "lt", "less_than" -> LESS_THAN
                "lte", "less_or_equal" -> LESS_OR_EQUAL
                else -> error("Unsupported ask_user condition operator: $value")
            }
        }
    }

    sealed interface ConditionExpression {
        data class Predicate(
            val questionId: String,
            val operator: ConditionOperator,
            val value: JsonElement? = null,
        ) : ConditionExpression

        data class All(val expressions: List<ConditionExpression>) : ConditionExpression
        data class Any(val expressions: List<ConditionExpression>) : ConditionExpression
        data class Not(val expression: ConditionExpression) : ConditionExpression

        fun referencedQuestionIds(): Set<String> = when (this) {
            is Predicate -> setOf(questionId)
            is All -> expressions.flatMapTo(hashSetOf()) { it.referencedQuestionIds() }
            is Any -> expressions.flatMapTo(hashSetOf()) { it.referencedQuestionIds() }
            is Not -> expression.referencedQuestionIds()
        }
    }

    data class OptionDetails(
        val value: String,
        val label: String = value,
        val badge: String = "",
        val icon: String = "",
        val imageUrl: String = "",
    )

    data class Question(
        val id: String,
        val question: String,
        val options: List<String>,
        val optionDetails: List<OptionDetails> = emptyList(),
        val selectionType: SelectionType,
        val required: Boolean,
        val description: String = "",
        val placeholder: String = "",
        val defaultValue: String? = null,
        val defaultValues: List<String> = emptyList(),
        val minLength: Int? = null,
        val maxLength: Int? = null,
        val minItems: Int? = null,
        val maxItems: Int? = null,
        val minValue: Double? = null,
        val maxValue: Double? = null,
        val step: Double? = null,
        val unit: String = "",
        val danger: Boolean = false,
        val timeoutSeconds: Int? = null,
        val visibleIf: ConditionExpression? = null,
        val presentation: String = "auto",
    ) {
        fun hasConfirmationCountdown(): Boolean =
            selectionType == SelectionType.CONFIRM && (danger || timeoutSeconds != null)
    }

    data class Request(val questions: List<Question>) {
        /**
         * Returns the shortest deadline for the safety-sensitive confirmation questions in this
         * request. Plain binary questions intentionally do not expire unless explicitly marked
         * dangerous or given a timeout.
         */
        fun confirmationTimeoutSeconds(): Int? = questions
            .asSequence()
            .filter { it.selectionType == SelectionType.CONFIRM && (it.danger || it.timeoutSeconds != null) }
            .map { it.timeoutSeconds ?: DEFAULT_CONFIRM_TIMEOUT_SECONDS }
            .minOrNull()
    }

    fun parseRequest(input: String): Result<Request> = runCatching {
        require(input.length <= MAX_ANSWER_LENGTH * 2) {
            "ask_user request is too large"
        }
        parseRequest(normalizeRequestElement(json.parseToJsonElement(input.ifBlank { "{}" })))
            .getOrThrow()
    }

    /**
     * Provider tool adapters normally hand us an object, but compatible Gemini/OpenAI gateways
     * have been observed to wrap arguments in a JSON string or in an `arguments`/`input` member.
     * Unwrap those transport-only envelopes before applying the canonical contract. We deliberately
     * A legacy singular `question` is accepted only as a compatibility envelope and is
     * normalized to the canonical `questions` array before validation.
     */
    private fun normalizeRequestElement(element: JsonElement): JsonElement {
        var current = element
        repeat(3) {
            current = when (current) {
                is JsonPrimitive -> {
                    val primitive = current
                    if (!primitive.isString) return primitive
                    runCatching { json.parseToJsonElement(primitive.content) }.getOrDefault(primitive)
                }
                is JsonObject -> {
                    val envelope = current["arguments"] ?: current["input"] ?: current["parameters"] ?: current["args"] ?: current["payload"] ?: current["form"]
                    when {
                        current["questions"] != null -> {
                            val questions = current["questions"]
                            if (questions is JsonPrimitive && questions.isString) {
                                val parsed = runCatching { json.parseToJsonElement(questions.content) }.getOrNull()
                                when (parsed) {
                                    is JsonArray -> JsonObject(mapOf("questions" to parsed))
                                    is JsonObject -> JsonObject(mapOf("questions" to JsonArray(listOf(parsed))))
                                    else -> return current
                                }
                            } else if (questions is JsonObject) {
                                JsonObject(mapOf("questions" to JsonArray(listOf(questions))))
                            } else return current
                        }
                        envelope != null -> envelope
                        current["items"] is JsonArray -> JsonObject(mapOf("questions" to current["items"]!!))
                        current["fields"] is JsonArray -> JsonObject(mapOf("questions" to current["fields"]!!))
                        current["question"] is JsonObject -> {
                            val question = current["question"] as JsonObject
                            val normalized = if (question["id"] == null) buildJsonObject {
                                question.forEach { (key, value) -> put(key, value) }
                                put("id", "question_1")
                            } else question
                            JsonObject(mapOf("questions" to JsonArray(listOf(normalized))))
                        }
                        current["question"] is JsonPrimitive -> {
                            val sourceObject = current as JsonObject
                            val questionText = (sourceObject["question"] as JsonPrimitive).content
                            val question = buildJsonObject {
                                sourceObject.forEach { (key, value) -> if (key != "question") put(key, value) }
                                if (sourceObject["id"] == null) put("id", "question_1")
                                put("question", questionText)
                            }
                            JsonObject(mapOf("questions" to JsonArray(listOf(question))))
                        }
                        else -> return current
                    }
                }
                else -> return current
            }
        }
        return current
    }

    fun parseRequest(element: JsonElement): Result<Request> = runCatching {
        val normalized = normalizeRequestElement(element)
        val root = normalized as? JsonObject
            ?: error("ask_user arguments must be a JSON object")
        require(root.keys.all { it == "questions" }) { "ask_user only accepts questions at the top level" }
        val questionsElement = root["questions"]
            ?: error("ask_user arguments must include questions")
        val questions = questionsElement as? JsonArray
            ?: error("ask_user questions must be an array")
        require(questions.isNotEmpty()) { "ask_user requires at least one question" }
        require(questions.size <= MAX_QUESTIONS) {
            "ask_user supports at most $MAX_QUESTIONS questions"
        }

        val parsed = questions.mapIndexed { index, element ->
            val rawObject = element as? JsonObject
                ?: error("ask_user question[$index] must be an object")
            val normalizedObject = if (rawObject["id"] == null && rawObject["question"] is JsonPrimitive) {
                JsonObject(rawObject + ("id" to JsonPrimitive("question_${index + 1}")))
            } else rawObject
            require(normalizedObject.keys.all { it in AskUserContract.questionFields }) {
                "ask_user question[$index] has unknown fields: ${normalizedObject.keys - AskUserContract.questionFields}. Use selection_type for type and presentation for layout."
            }
            // Some compatible gateways materialize omitted optional parameters as null.
            val obj = JsonObject(normalizedObject.filter { (key, value) -> value !is JsonNull || key in setOf("id", "question") })
            val id = obj.stringField("id", "question[$index].id")
            val question = obj.stringField("question", "question[$index].question")
            require(id.length <= MAX_ID_LENGTH) { "ask_user question[$index].id is too long" }
            require(question.length <= MAX_QUESTION_LENGTH) {
                "ask_user question[$index].question is too long"
            }

            val optionDetails = mutableListOf<OptionDetails>()
            var options = when (val optionsElement = obj["options"]) {
                null -> emptyList()
                is JsonArray -> optionsElement.mapIndexed { optionIndex, option ->
                    val value: String
                    val label: String
                    val badge: String
                    val icon: String
                    val imageUrl: String
                    when (option) {
                        is JsonPrimitive -> {
                            require(option.isQuotedString()) {
                                "ask_user question[$index].options[$optionIndex] must be a string or object"
                            }
                            value = option.content.trim()
                            label = value
                            badge = ""
                            icon = ""
                            imageUrl = ""
                        }
                        is JsonObject -> {
                            value = option.stringField(
                                "value",
                                "question[$index].options[$optionIndex].value",
                            )
                            label = option.optionalString(
                                "label",
                                "question[$index].options[$optionIndex].label",
                            )?.ifBlank { value } ?: value
                            badge = option.optionalString(
                                "badge",
                                "question[$index].options[$optionIndex].badge",
                            ).orEmpty()
                            icon = option.optionalString(
                                "icon",
                                "question[$index].options[$optionIndex].icon",
                            ).orEmpty()
                            imageUrl = option.optionalString(
                                "image",
                                "question[$index].options[$optionIndex].image",
                            ).orEmpty()
                        }
                        else -> error(
                            "ask_user question[$index].options[$optionIndex] must be a string or object"
                        )
                    }
                    require(value.isNotEmpty()) {
                        "ask_user question[$index].options[$optionIndex].value must not be blank"
                    }
                    require(value.length <= MAX_OPTION_LENGTH) {
                        "ask_user question[$index].options[$optionIndex].value is too long"
                    }
                    require(label.length <= MAX_OPTION_LENGTH) {
                        "ask_user question[$index].options[$optionIndex].label is too long"
                    }
                    require(badge.length <= MAX_OPTION_LENGTH) {
                        "ask_user question[$index].options[$optionIndex].badge is too long"
                    }
                    require(icon.length <= MAX_OPTION_LENGTH) {
                        "ask_user question[$index].options[$optionIndex].icon is too long"
                    }
                    require(imageUrl.length <= 2048) {
                        "ask_user question[$index].options[$optionIndex].image is too long"
                    }
                    optionDetails += OptionDetails(value, label, badge, icon, imageUrl)
                    value
                }
                else -> error("ask_user question[$index].options must be an array")
            }
            obj["option_details"]?.let { detailsElement ->
                val details = detailsElement as? JsonArray
                    ?: error("ask_user question[$index].option_details must be an array")
                val extraDetails = details.mapIndexed { detailIndex, detail ->
                    val detailObject = detail as? JsonObject
                        ?: error("ask_user question[$index].option_details[$detailIndex] must be an object")
                    val value = detailObject.stringField(
                        "value",
                        "question[$index].option_details[$detailIndex].value",
                    )
                    val label = detailObject.optionalString(
                        "label",
                        "question[$index].option_details[$detailIndex].label",
                    )?.ifBlank { value } ?: value
                    val badge = detailObject.optionalString(
                        "badge",
                        "question[$index].option_details[$detailIndex].badge",
                    ).orEmpty()
                    val icon = detailObject.optionalString(
                        "icon",
                        "question[$index].option_details[$detailIndex].icon",
                    ).orEmpty()
                    val imageUrl = detailObject.optionalString(
                        "image",
                        "question[$index].option_details[$detailIndex].image",
                    ).orEmpty()
                    require(value.length <= MAX_OPTION_LENGTH) {
                        "ask_user question[$index].option_details[$detailIndex].value is too long"
                    }
                    require(label.length <= MAX_OPTION_LENGTH) {
                        "ask_user question[$index].option_details[$detailIndex].label is too long"
                    }
                    require(badge.length <= MAX_OPTION_LENGTH) {
                        "ask_user question[$index].option_details[$detailIndex].badge is too long"
                    }
                    require(icon.length <= MAX_OPTION_LENGTH) {
                        "ask_user question[$index].option_details[$detailIndex].icon is too long"
                    }
                    require(imageUrl.length <= 2048) {
                        "ask_user question[$index].option_details[$detailIndex].image is too long"
                    }
                    OptionDetails(value, label, badge, icon, imageUrl)
                }
                require(extraDetails.map { it.value }.distinct().size == extraDetails.size) {
                    "ask_user question[$index].option_details must be unique"
                }
                if (options.isEmpty()) {
                    options = extraDetails.map(OptionDetails::value)
                    optionDetails += extraDetails
                } else {
                    require(extraDetails.all { it.value in options }) {
                        "ask_user question[$index].option_details contains an unknown option"
                    }
                    optionDetails.removeAll { current -> extraDetails.any { it.value == current.value } }
                    optionDetails += extraDetails
                }
            }
            require(options.size <= MAX_OPTIONS) {
                "ask_user question[$index] has too many options"
            }
            require(options.distinct().size == options.size) {
                "ask_user question[$index].options must be unique"
            }

            val selectionType = SelectionType.fromWire(
                obj["selection_type"]?.let { primitive ->
                    val value = primitive as? JsonPrimitive
                        ?: error("ask_user question[$index].selection_type must be a string")
                    require(value.isQuotedString()) {
                        "ask_user question[$index].selection_type must be a string"
                    }
                    value.content
                }
            )
            if (selectionType == SelectionType.SINGLE || selectionType == SelectionType.MULTI) {
                require(options.isNotEmpty()) {
                    "ask_user question[$index] requires options for ${selectionType.wireValue} selection"
                }
            }
            val presentation = obj.optionalString("presentation", "question[$index].presentation") ?: "auto"
            require(presentation in AskUserContract.presentations) { "Unsupported ask_user presentation: $presentation" }
            require(presentation == "auto" || (selectionType in setOf(SelectionType.TEXT, SelectionType.SINGLE, SelectionType.MULTI) && options.isNotEmpty())) {
                "ask_user presentation requires text suggestions, single or multi options"
            }
            require(options.isEmpty() || selectionType in setOf(SelectionType.TEXT, SelectionType.SINGLE, SelectionType.MULTI)) {
                "ask_user options require text, single or multi selection"
            }

            val required = obj["required"]?.let { value ->
                val primitive = value as? JsonPrimitive
                    ?: error("ask_user question[$index].required must be a boolean")
                primitive.booleanOrNull
                    ?: error("ask_user question[$index].required must be a boolean")
            } ?: true

            val description = obj.optionalString("description", "question[$index].description")
                ?.also {
                    require(it.length <= MAX_DESCRIPTION_LENGTH) {
                        "ask_user question[$index].description is too long"
                    }
                }
                .orEmpty()
            val placeholder = obj.optionalString("placeholder", "question[$index].placeholder")
                ?.also {
                    require(it.length <= MAX_PLACEHOLDER_LENGTH) {
                        "ask_user question[$index].placeholder is too long"
                    }
                }
                .orEmpty()

            val minLength = obj.optionalInt("min_length", "question[$index].min_length")
            val maxLength = obj.optionalInt("max_length", "question[$index].max_length")
            if (selectionType == SelectionType.TEXT) {
                require(minLength == null || minLength in 0..MAX_TEXT_ANSWER_LENGTH) {
                    "ask_user question[$index].min_length is out of range"
                }
                require(maxLength == null || maxLength in 0..MAX_TEXT_ANSWER_LENGTH) {
                    "ask_user question[$index].max_length is out of range"
                }
                require(minLength == null || maxLength == null || minLength <= maxLength) {
                    "ask_user question[$index].min_length must not exceed max_length"
                }
            } else {
                require(minLength == null && maxLength == null) {
                    "ask_user length constraints require text selection"
                }
            }

            val minItems = obj.optionalInt("min_items", "question[$index].min_items")
            val maxItems = obj.optionalInt("max_items", "question[$index].max_items")
            if (selectionType == SelectionType.MULTI) {
                require(minItems == null || minItems in 0..MAX_OPTIONS) {
                    "ask_user question[$index].min_items is out of range"
                }
                require(maxItems == null || maxItems in 0..MAX_OPTIONS) {
                    "ask_user question[$index].max_items is out of range"
                }
                require(minItems == null || maxItems == null || minItems <= maxItems) {
                    "ask_user question[$index].min_items must not exceed max_items"
                }
                require(minItems == null || minItems <= options.size) {
                    "ask_user question[$index].min_items exceeds available options"
                }
                require(maxItems == null || maxItems <= options.size) {
                    "ask_user question[$index].max_items exceeds available options"
                }
            } else {
                require(minItems == null && maxItems == null) {
                    "ask_user item constraints require multi selection"
                }
            }

            val minValue = obj.optionalDouble("min_value", "question[$index].min_value")
            val maxValue = obj.optionalDouble("max_value", "question[$index].max_value")
            val configuredStep = obj.optionalDouble("step", "question[$index].step")
            val unit = obj.optionalString("unit", "question[$index].unit").orEmpty()
            require(unit.length <= 64) { "ask_user question[$index].unit is too long" }
            val numericSelection = selectionType == SelectionType.SLIDER ||
                selectionType == SelectionType.RATING
            val effectiveMinValue: Double?
            val effectiveMaxValue: Double?
            val effectiveStep: Double?
            if (numericSelection) {
                effectiveMinValue = if (selectionType == SelectionType.RATING) minValue ?: 1.0 else minValue
                effectiveMaxValue = if (selectionType == SelectionType.RATING) maxValue ?: 5.0 else maxValue
                require(effectiveMinValue != null && effectiveMaxValue != null) {
                    "ask_user question[$index] requires min_value and max_value"
                }
                require(effectiveMinValue.isFinite() && effectiveMaxValue.isFinite()) {
                    "ask_user question[$index] numeric bounds must be finite"
                }
                require(kotlin.math.abs(effectiveMinValue) <= MAX_NUMERIC_MAGNITUDE && kotlin.math.abs(effectiveMaxValue) <= MAX_NUMERIC_MAGNITUDE) {
                    "ask_user numeric bounds must be within +/-$MAX_NUMERIC_MAGNITUDE"
                }
                require(effectiveMinValue < effectiveMaxValue) {
                    "ask_user question[$index].min_value must be less than max_value"
                }
                effectiveStep = configuredStep ?: if (selectionType == SelectionType.RATING) 1.0 else {
                    ((effectiveMaxValue - effectiveMinValue) / 100.0).coerceAtLeast(MIN_NUMERIC_STEP)
                }
                require(effectiveStep.isFinite() && effectiveStep >= MIN_NUMERIC_STEP) {
                    "ask_user question[$index].step must be at least $MIN_NUMERIC_STEP"
                }
                require(effectiveStep <= effectiveMaxValue - effectiveMinValue) {
                    "ask_user question[$index].step exceeds numeric range"
                }
                require((effectiveMaxValue - effectiveMinValue) / effectiveStep <= 1e9) { "ask_user numeric range exceeds 1e9 steps" }
                require(effectiveStep >= Math.ulp(maxOf(kotlin.math.abs(effectiveMinValue), kotlin.math.abs(effectiveMaxValue)))) {
                    "ask_user step is below the precision available for this numeric range"
                }
                if (selectionType == SelectionType.RATING) {
                    val ratingSteps = (effectiveMaxValue - effectiveMinValue) / effectiveStep
                    require(
                        ratingSteps.isFinite() &&
                            ratingSteps >= 1.0 &&
                            ratingSteps <= (MAX_RATING_STEPS - 1).toDouble() &&
                            kotlin.math.abs(ratingSteps - kotlin.math.round(ratingSteps)) <= 1e-7
                    ) {
                        "ask_user rating range must contain at most $MAX_RATING_STEPS evenly spaced values"
                    }
                }
            } else {
                require(minValue == null && maxValue == null && configuredStep == null) {
                    "ask_user numeric bounds require slider or rating selection"
                }
                require(unit.isBlank()) { "ask_user unit requires slider or rating selection" }
                effectiveMinValue = null
                effectiveMaxValue = null
                effectiveStep = null
            }

            val danger = obj.optionalBoolean("danger", "question[$index].danger") ?: false
            val timeoutSeconds = obj.optionalInt("timeout_seconds", "question[$index].timeout_seconds")
            if (selectionType == SelectionType.CONFIRM) {
                require(timeoutSeconds == null || timeoutSeconds in 1..3600) {
                    "ask_user question[$index].timeout_seconds is out of range"
                }
                require(options.isEmpty()) {
                    "ask_user confirm selection does not accept options"
                }
            } else {
                require(!danger && timeoutSeconds == null) {
                    "ask_user danger and timeout_seconds require confirm selection"
                }
            }

            val visibleIf = obj["visible_if"]?.let {
                parseCondition(it, "question[$index].visible_if")
            }

            require(obj["default_values"] == null || selectionType == SelectionType.MULTI) { "ask_user default_values requires multi selection" }
            require(obj["default_values"] == null || obj["default"] == null) { "ask_user use default_values or default, not both" }
            val defaultElement = obj["default_values"] ?: obj["default"]
            var defaultValue: String? = null
            var defaultValues: List<String> = emptyList()
            when (selectionType) {
                SelectionType.MULTI -> if (defaultElement != null) {
                    defaultValues = when (defaultElement) {
                        is JsonArray -> defaultElement.mapIndexed { optionIndex, item ->
                            val primitive = item as? JsonPrimitive
                                ?: error("ask_user question[$index].default[$optionIndex] must be a string")
                            require(primitive.isQuotedString()) {
                                "ask_user question[$index].default must be an array of strings"
                            }
                            primitive.content.trim()
                        }
                        is JsonPrimitive -> {
                            require(defaultElement.isQuotedString()) {
                                "ask_user question[$index].default must be an array of strings"
                            }
                            defaultElement.content.split(',').map(String::trim)
                        }
                        else -> error("ask_user question[$index].default must be an array of strings")
                    }.filter(String::isNotBlank)
                    require(defaultValues.distinct().size == defaultValues.size) {
                        "ask_user question[$index].default contains duplicates"
                    }
                    require(defaultValues.all { it in options }) {
                        "ask_user question[$index].default contains an invalid option"
                    }
                    require(maxItems == null || defaultValues.size <= maxItems) {
                        "ask_user question[$index].default exceeds max_items"
                    }
                    require(minItems == null || defaultValues.size >= minItems) {
                        "ask_user question[$index].default is below min_items"
                    }
                }
                SelectionType.SINGLE,
                SelectionType.TEXT,
                    -> if (defaultElement != null) {
                    val primitive = defaultElement as? JsonPrimitive
                        ?: error("ask_user question[$index].default must be a string")
                    require(primitive.isQuotedString()) {
                        "ask_user question[$index].default must be a string"
                    }
                    val parsedDefaultValue = if (selectionType == SelectionType.SINGLE) {
                        primitive.content.trim()
                    } else {
                        primitive.content
                    }
                    defaultValue = parsedDefaultValue
                    if (selectionType == SelectionType.SINGLE && parsedDefaultValue.isNotBlank()) {
                        require(parsedDefaultValue in options) {
                            "ask_user question[$index].default contains an invalid option"
                        }
                    }
                    if (selectionType == SelectionType.TEXT) {
                        require(parsedDefaultValue.length <= (maxLength ?: MAX_TEXT_ANSWER_LENGTH)) {
                            "ask_user question[$index].default exceeds max_length"
                        }
                        require(parsedDefaultValue.isBlank() || parsedDefaultValue.length >= (minLength ?: 0)) {
                            "ask_user question[$index].default is below min_length"
                        }
                    }
                }

                SelectionType.SLIDER,
                SelectionType.RATING,
                    -> if (defaultElement != null) {
                    val primitive = defaultElement as? JsonPrimitive
                        ?: error("ask_user question[$index].default must be a number")
                    val numericDefault = primitive.numericOrNull()
                        ?: error("ask_user question[$index].default must be a number")
                    require(numericDefault.isFinite()) {
                        "ask_user question[$index].default must be finite"
                    }
                    require(numericDefault >= effectiveMinValue!! && numericDefault <= effectiveMaxValue!!) {
                        "ask_user question[$index].default is outside numeric range"
                    }
                    require(isStepAligned(numericDefault, effectiveMinValue, effectiveStep!!)) {
                        "ask_user question[$index].default does not match step"
                    }
                    defaultValue = primitive.content
                }

                SelectionType.CONFIRM -> if (defaultElement != null) {
                    val primitive = defaultElement as? JsonPrimitive
                        ?: error("ask_user question[$index].default must be a boolean")
                    val boolDefault = primitive.booleanOrNull
                        ?: primitive.content.toBooleanStrictOrNull()
                        ?: error("ask_user question[$index].default must be a boolean")
                    // Legacy persisted forms may contain true. Never preselect approval.
                    defaultValue = if (boolDefault) null else "false"
                }

                SelectionType.DATE,
                SelectionType.TIME,
                    -> if (defaultElement != null) {
                    val primitive = defaultElement as? JsonPrimitive
                        ?: error("ask_user question[$index].default must be a string")
                    require(primitive.isQuotedString()) {
                        "ask_user question[$index].default must be a string"
                    }
                    val value = primitive.content
                    require(
                        if (selectionType == SelectionType.DATE) isValidDate(value) else isValidTime(value)
                    ) {
                        "ask_user question[$index].default has an invalid ${selectionType.wireValue}"
                    }
                    defaultValue = value
                }
            }

            Question(
                id = id,
                question = question,
                options = options,
                selectionType = selectionType,
                required = required,
                description = description,
                placeholder = placeholder,
                defaultValue = defaultValue,
                defaultValues = defaultValues,
                minLength = minLength,
                maxLength = maxLength,
                minItems = minItems,
                maxItems = maxItems,
                optionDetails = optionDetails,
                minValue = effectiveMinValue,
                maxValue = effectiveMaxValue,
                step = effectiveStep,
                unit = unit,
                danger = danger,
                timeoutSeconds = timeoutSeconds,
                visibleIf = visibleIf,
                presentation = presentation,
            )
        }
        require(parsed.map(Question::id).distinct().size == parsed.size) {
            "ask_user question ids must be unique"
        }
        validateConditionReferences(parsed)
        Request(parsed)
    }

    /**
     * Validates and normalizes an answer. Legacy multi-select strings are
     * accepted for old persisted sessions and rewritten as JSON arrays.
     */
    fun validateAnswer(request: Request, answer: String): Result<String> = runCatching {
        require(answer.length <= MAX_ANSWER_LENGTH) { "ask_user answer is too large" }
        val root = json.parseToJsonElement(answer) as? JsonObject
            ?: error("ask_user answer must be a JSON object")
        val answers = root["answers"] as? JsonObject
            ?: error("ask_user answer must include an answers object")
        val knownIds = request.questions.mapTo(hashSetOf(), Question::id)
        require(answers.keys.all { it in knownIds }) {
            "ask_user answer contains an unknown question id"
        }

        val normalized = buildJsonObject {
            put("answers", buildJsonObject {
                val visible = visibleAnswers(request, answers)
                request.questions.forEach { question ->
                    if (!isQuestionVisible(question, visible)) return@forEach
                    val value = answers[question.id]
                    if (value == null || value is kotlinx.serialization.json.JsonNull) {
                        require(!question.required) {
                            "ask_user answer is missing required question ${question.id}"
                        }
                        return@forEach
                    }

                    when (question.selectionType) {
                        SelectionType.MULTI -> {
                            val values = when (value) {
                                is JsonArray -> value.map { item ->
                                    val primitive = item as? JsonPrimitive
                                        ?: error("ask_user multi answer must contain strings")
                                    require(primitive.isQuotedString()) {
                                        "ask_user multi answer must contain strings"
                                    }
                                    primitive.content.trim()
                                }
                                is JsonPrimitive -> {
                                    require(value.isQuotedString()) {
                                        "ask_user multi answer must be an array of strings"
                                    }
                                    value.content.split(',').map(String::trim)
                                }
                                else -> error("ask_user multi answer must be an array of strings")
                            }.filter(String::isNotBlank)
                            require(values.isNotEmpty() || !question.required) {
                                "ask_user answer for ${question.id} must not be empty"
                            }
                            require(values.distinct().size == values.size) {
                                "ask_user answer for ${question.id} contains duplicates"
                            }
                            require(question.minItems == null || values.size >= question.minItems) {
                                "ask_user answer for ${question.id} has too few selections"
                            }
                            require(question.maxItems == null || values.size <= question.maxItems) {
                                "ask_user answer for ${question.id} has too many selections"
                            }
                            require(values.all { it in question.options }) {
                                "ask_user answer for ${question.id} contains an invalid option"
                            }
                            if (values.isNotEmpty()) {
                                put(question.id, buildJsonArray {
                                    values.forEach { add(JsonPrimitive(it)) }
                                })
                            }
                        }
                        SelectionType.SINGLE -> {
                            val primitive = value as? JsonPrimitive
                            require(primitive != null && primitive.isQuotedString()) {
                                "ask_user single answer must be a string"
                            }
                            val selected = primitive.content.trim()
                            require(selected.isNotEmpty() || !question.required) {
                                "ask_user answer for ${question.id} must not be empty"
                            }
                            if (selected.isNotEmpty()) {
                                require(selected in question.options) {
                                    "ask_user answer for ${question.id} contains an invalid option"
                                }
                                put(question.id, selected)
                            }
                        }
                        SelectionType.TEXT -> {
                            val primitive = value as? JsonPrimitive
                            require(primitive != null && primitive.isQuotedString()) {
                                "ask_user text answer must be a string"
                            }
                            val text = primitive.content
                            require(text.isNotBlank() || !question.required) {
                                "ask_user answer for ${question.id} must not be empty"
                            }
                            require(text.length <= MAX_TEXT_ANSWER_LENGTH) {
                                "ask_user answer for ${question.id} is too long"
                            }
                            require(text.isBlank() || question.minLength == null || text.length >= question.minLength) {
                                "ask_user answer for ${question.id} is shorter than min_length"
                            }
                            require(question.maxLength == null || text.length <= question.maxLength) {
                                "ask_user answer for ${question.id} exceeds max_length"
                            }
                            if (text.isNotBlank()) put(question.id, text)
                        }
                        SelectionType.SLIDER,
                        SelectionType.RATING,
                            -> {
                            val primitive = value as? JsonPrimitive
                            val numericValue = primitive?.numericOrNull()
                                ?: error("ask_user numeric answer must be a number")
                            require(numericValue.isFinite()) {
                                "ask_user numeric answer must be finite"
                            }
                            require(
                                numericValue >= question.minValue!! &&
                                    numericValue <= question.maxValue!!
                            ) {
                                "ask_user answer for ${question.id} is outside numeric range"
                            }
                            require(isStepAligned(numericValue, question.minValue, question.step!!)) {
                                "ask_user answer for ${question.id} does not match step"
                            }
                            put(question.id, numericValue)
                        }
                        SelectionType.CONFIRM -> {
                            val primitive = value as? JsonPrimitive
                            val boolValue = primitive?.booleanOrNull
                                ?: primitive?.content?.toBooleanStrictOrNull()
                                ?: error("ask_user confirmation answer must be boolean")
                            put(question.id, boolValue)
                        }
                        SelectionType.DATE,
                        SelectionType.TIME,
                            -> {
                            val primitive = value as? JsonPrimitive
                            require(primitive != null && primitive.isQuotedString()) {
                                "ask_user ${question.selectionType.wireValue} answer must be a string"
                            }
                            require(
                                if (question.selectionType == SelectionType.DATE) {
                                    isValidDate(primitive.content)
                                } else {
                                    isValidTime(primitive.content)
                                }
                            ) {
                                "ask_user answer for ${question.id} has an invalid ${question.selectionType.wireValue}"
                            }
                            put(question.id, primitive.content)
                        }
                    }
                }
            })
        }
        json.encodeToString(normalized)
    }

    fun isQuestionVisible(question: Question, answers: Map<String, JsonElement>): Boolean =
        question.visibleIf?.evaluate(answers) ?: true

    /** Strip answers of hidden parents before evaluating dependent questions. */
    fun visibleAnswers(request: Request, answers: Map<String, JsonElement>): Map<String, JsonElement> {
        val result = answers.filterKeys { id -> request.questions.any { it.id == id } }.toMutableMap()
        repeat(request.questions.size) {
            val hidden = request.questions.filterNot { isQuestionVisible(it, result) }.map { it.id }
            val changed = hidden.any { it in result }
            hidden.forEach(result::remove)
            if (!changed) return result
        }
        return result
    }

    private fun ConditionExpression.evaluate(answers: Map<String, JsonElement>): Boolean = when (this) {
        is ConditionExpression.Predicate -> {
            val actual = answers[questionId]
            when (operator) {
                ConditionOperator.EXISTS -> actual != null && !actual.isEmptyAnswer()
                ConditionOperator.EQUALS -> actual.scalarEquals(value)
                ConditionOperator.NOT_EQUALS -> !actual.scalarEquals(value)
                ConditionOperator.CONTAINS -> actual.containsValue(value)
                ConditionOperator.IN -> when {
                    value !is JsonArray -> false
                    actual is JsonArray -> actual.any { item ->
                        value.any { expected -> item.scalarEquals(expected) }
                    }
                    else -> value.any { expected -> actual.scalarEquals(expected) }
                }
                ConditionOperator.GREATER_THAN -> actual.compareNumber(value) { a, b -> a > b }
                ConditionOperator.GREATER_OR_EQUAL -> actual.compareNumber(value) { a, b -> a >= b }
                ConditionOperator.LESS_THAN -> actual.compareNumber(value) { a, b -> a < b }
                ConditionOperator.LESS_OR_EQUAL -> actual.compareNumber(value) { a, b -> a <= b }
            }
        }
        is ConditionExpression.All -> expressions.all { it.evaluate(answers) }
        is ConditionExpression.Any -> expressions.any { it.evaluate(answers) }
        is ConditionExpression.Not -> !expression.evaluate(answers)
    }

    private fun parseCondition(
        element: JsonElement,
        path: String,
        depth: Int = 0,
    ): ConditionExpression {
        require(depth <= 8) { "ask_user condition nesting is too deep" }
        val obj = (element as? JsonObject)?.let { JsonObject(it.filterValues { value -> value !is JsonNull }) }
            ?: error("ask_user $path must be an object")
        if ("nodes" in obj || "root" in obj) {
            require(obj.keys == setOf("nodes", "root")) { "ask_user $path requires root and nodes only" }
            val nodes = obj["nodes"] as? JsonArray ?: error("ask_user $path.nodes must be an array")
            require(nodes.size in 1..32) { "ask_user condition graph supports 1..32 nodes" }
            val indexed = nodes.map { it as? JsonObject ?: error("Condition nodes must be objects") }.associateBy { it.stringField("id", "$path.nodes.id") }
            require(indexed.size == nodes.size) { "Duplicate condition node id" }
            val visiting = hashSetOf<String>()
            val reachable = hashSetOf<String>()
            var visits = 0
            fun build(id: String, graphDepth: Int): ConditionExpression {
                require(++visits <= 512) { "Condition graph expands beyond 512 nodes" }
                require(graphDepth + depth <= 8 && visiting.add(id)) { "Condition graph contains a cycle or exceeds depth 8" }
                reachable += id
                try {
                    val node = indexed[id]?.let { JsonObject(it.filterValues { value -> value !is JsonNull }) } ?: error("Unknown condition node: $id")
                    val kind = node.stringField("kind", "$path.nodes.kind")
                    if (kind == "predicate") return parseCondition(JsonObject(node - "id" - "kind"), "$path.nodes.$id", depth + graphDepth)
                    require(node.keys == setOf("id", "kind", "children")) { "Condition group accepts id, kind and children only" }
                    val children = node["children"] as? JsonArray ?: error("Condition group requires children")
                    require(children.isNotEmpty() && children.size <= 32) { "Condition group requires 1..32 children" }
                    val expressions = children.map { child ->
                        val name = child as? JsonPrimitive ?: error("Condition child must be an id")
                        require(name.isQuotedString()) { "Condition child must be an id string" }
                        build(name.content, graphDepth + 1)
                    }
                    return when (kind) {
                        "all" -> ConditionExpression.All(expressions)
                        "any" -> ConditionExpression.Any(expressions)
                        "not" -> { require(expressions.size == 1); ConditionExpression.Not(expressions.single()) }
                        else -> error("Unsupported condition node kind: $kind")
                    }
                } finally { visiting.remove(id) }
            }
            val result = build(obj.stringField("root", "$path.root"), 0)
            require(reachable.size == indexed.size) { "Condition graph contains unused nodes" }
            return result
        }
        require(obj.keys.all { it in setOf("all", "any", "not", "question_id", "operator", "value", "values") }) { "ask_user $path contains unknown condition fields" }
        val groups = listOf("all", "any", "not").count(obj::containsKey)
        require(groups <= 1 && (groups == 0 || obj.size == 1)) { "ask_user $path must contain exactly one group or predicate" }
        obj["all"]?.let { allElement ->
            val expressions = allElement as? JsonArray
                ?: error("ask_user $path.all must be an array")
            require(expressions.isNotEmpty()) { "ask_user $path.all must not be empty" }
            return ConditionExpression.All(
                expressions.mapIndexed { index, child ->
                    parseCondition(child, "$path.all[$index]", depth + 1)
                }
            )
        }
        obj["any"]?.let { anyElement ->
            val expressions = anyElement as? JsonArray
                ?: error("ask_user $path.any must be an array")
            require(expressions.isNotEmpty()) { "ask_user $path.any must not be empty" }
            return ConditionExpression.Any(
                expressions.mapIndexed { index, child ->
                    parseCondition(child, "$path.any[$index]", depth + 1)
                }
            )
        }
        obj["not"]?.let { child ->
            return ConditionExpression.Not(parseCondition(child, "$path.not", depth + 1))
        }

        val questionId = obj.stringField("question_id", "$path.question_id")
        val operator = ConditionOperator.fromWire(
            obj["operator"]?.let { value ->
                val primitive = value as? JsonPrimitive
                    ?: error("ask_user $path.operator must be a string")
                require(primitive.isQuotedString()) { "ask_user $path.operator must be a string" }
                primitive.content
            }
        )
        require(obj["values"] == null || (operator == ConditionOperator.IN && obj["values"] is JsonArray && obj["value"] == null)) { "ask_user values requires operator=in and an array; do not also supply value" }
        val value = obj["values"] ?: obj["value"]
        require(operator != ConditionOperator.IN || value is JsonArray) { "ask_user operator=in requires an array of values" }
        require(operator == ConditionOperator.EXISTS || value != null) {
            "ask_user $path.value is required for ${operator.wireValue}"
        }
        return ConditionExpression.Predicate(questionId, operator, value)
    }

    private fun validateConditionReferences(questions: List<Question>) {
        val ids = questions.mapTo(hashSetOf(), Question::id)
        questions.forEach { question ->
            question.visibleIf?.referencedQuestionIds()?.forEach { reference ->
                require(reference in ids) {
                    "ask_user question ${question.id} references unknown question $reference"
                }
                require(reference != question.id) {
                    "ask_user question ${question.id} cannot reference itself"
                }
            }
        }

        val dependencies = questions.associate { it.id to it.visibleIf?.referencedQuestionIds().orEmpty() }
        val visiting = hashSetOf<String>()
        val visited = hashSetOf<String>()
        fun visit(id: String) {
            require(id !in visiting) { "ask_user visible_if conditions contain a cycle" }
            if (!visited.add(id)) return
            visiting.add(id)
            dependencies[id].orEmpty().forEach(::visit)
            visiting.remove(id)
        }
        questions.forEach { visit(it.id) }
    }

    private fun JsonObject.optionalDouble(name: String, path: String): Double? {
        val value = this[name] ?: return null
        val primitive = value as? JsonPrimitive
            ?: error("ask_user $path must be a number")
        return primitive.doubleOrNull
            ?.also { require(it.isFinite()) { "ask_user $path must be finite" } }
            ?: error("ask_user $path must be a number")
    }

    private fun JsonObject.optionalBoolean(name: String, path: String): Boolean? {
        val value = this[name] ?: return null
        val primitive = value as? JsonPrimitive
            ?: error("ask_user $path must be a boolean")
        return primitive.booleanOrNull
            ?: error("ask_user $path must be a boolean")
    }

    private fun isStepAligned(value: Double, minimum: Double?, step: Double): Boolean {
        if (minimum == null) return false
        return AskUserNumbers.isStepAligned(value, minimum, step)
    }

    private fun isValidDate(value: String): Boolean = runCatching {
        java.time.LocalDate.parse(value)
    }.isSuccess

    private fun isValidTime(value: String): Boolean = runCatching {
        java.time.LocalTime.parse(value)
    }.isSuccess

    private fun JsonElement?.isEmptyAnswer(): Boolean = when (this) {
        null, kotlinx.serialization.json.JsonNull -> true
        is JsonArray -> isEmpty()
        is JsonPrimitive -> content.isBlank()
        else -> false
    }

    private fun JsonElement?.scalarEquals(expected: JsonElement?): Boolean {
        if (this == null || expected == null) return false
        val actualPrimitive = this as? JsonPrimitive ?: return false
        val expectedPrimitive = expected as? JsonPrimitive ?: return false
        val actualNumber = actualPrimitive.numericOrNull()
        val expectedNumber = expectedPrimitive.numericOrNull()
        return if (actualNumber != null && expectedNumber != null) {
            actualNumber == expectedNumber
        } else {
            actualPrimitive.content == expectedPrimitive.content
        }
    }

    private fun JsonElement?.containsValue(expected: JsonElement?): Boolean = when (this) {
        is JsonArray -> any { item -> item.scalarEquals(expected) }
        is JsonPrimitive -> {
            val needle = (expected as? JsonPrimitive)?.content ?: return false
            content.contains(needle, ignoreCase = false)
        }
        else -> false
    }

    private fun JsonElement?.compareNumber(
        expected: JsonElement?,
        comparison: (Double, Double) -> Boolean,
    ): Boolean {
        val actualNumber = (this as? JsonPrimitive)?.numericOrNull() ?: return false
        val expectedNumber = (expected as? JsonPrimitive)?.numericOrNull() ?: return false
        return comparison(actualNumber, expectedNumber)
    }

    private fun JsonObject.stringField(name: String, path: String): String {
        val primitive = this[name] as? JsonPrimitive
            ?: error("ask_user $path must be a string")
        require(primitive.isQuotedString()) { "ask_user $path must be a string" }
        return primitive.content.trim().also {
            require(it.isNotEmpty()) { "ask_user $path must not be blank" }
        }
    }

    private fun JsonObject.optionalString(name: String, path: String): String? {
        val value = this[name] ?: return null
        val primitive = value as? JsonPrimitive
            ?: error("ask_user $path must be a string")
        require(primitive.isQuotedString()) { "ask_user $path must be a string" }
        return primitive.content
    }

    private fun JsonObject.optionalInt(name: String, path: String): Int? {
        val value = this[name] ?: return null
        val primitive = value as? JsonPrimitive
            ?: error("ask_user $path must be an integer")
        return primitive.content.toIntOrNull()
            ?: error("ask_user $path must be an integer")
    }

    private fun JsonPrimitive.isQuotedString(): Boolean = toString().startsWith('"')

    private fun JsonPrimitive.numericOrNull(): Double? =
        doubleOrNull ?: content.toDoubleOrNull()
}
