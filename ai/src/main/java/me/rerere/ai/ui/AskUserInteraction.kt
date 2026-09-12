package me.rerere.ai.ui

import kotlinx.serialization.json.*
import java.security.MessageDigest

/** Local UI state only. Provider request serializers must never include this metadata. */
object AskUserInteraction {
    const val KEY = "ask_user_interaction"
    data class Clock(val wall: Long, val elapsed: Long, val boot: Int)
    private fun fingerprint(input: String) = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun state(input: String, metadata: JsonObject?): JsonObject? = (metadata?.get(KEY) as? JsonObject)
        ?.takeIf { (it["fingerprint"] as? JsonPrimitive)?.content == fingerprint(input) }
    fun answers(input: String, metadata: JsonObject?): JsonObject = state(input, metadata)?.get("answers") as? JsonObject ?: JsonObject(emptyMap())
    fun displayAnswers(input: String, metadata: JsonObject?, approval: ToolApprovalState): JsonObject =
        if (approval is ToolApprovalState.Answered) runCatching {
            (Json.parseToJsonElement(approval.answer) as? JsonObject)?.get("answers") as? JsonObject
        }.getOrNull() ?: JsonObject(emptyMap()) else answers(input, metadata)
    fun isCurrent(input: String, metadata: JsonObject?) = state(input, metadata) != null
    fun initial(input: String, pendingAt: Long): JsonObject = buildJsonObject {
        put(AskUserProtocol.PENDING_AT_METADATA_KEY, pendingAt)
        put(KEY, buildJsonObject { put("fingerprint", fingerprint(input)); put("answers", JsonObject(emptyMap())); put("timers", JsonObject(emptyMap())) })
    }
    private fun timers(input: String, metadata: JsonObject?) = state(input, metadata)?.get("timers") as? JsonObject ?: JsonObject(emptyMap())
    fun remainingMillis(input: String, metadata: JsonObject?, id: String, clock: Clock): Long? {
        val timer = timers(input, metadata)[id] as? JsonObject
        if (timer != null) {
            if ((timer["boot"] as? JsonPrimitive)?.intOrNull == clock.boot && clock.boot >= 0) {
                (timer["elapsed_deadline"] as? JsonPrimitive)?.longOrNull?.let { return it - clock.elapsed }
            }
            return (timer["wall_deadline"] as? JsonPrimitive)?.longOrNull?.minus(clock.wall)
        }
        if (!isCurrent(input, metadata)) return (metadata?.get(AskUserProtocol.CONFIRM_DEADLINE_METADATA_KEY) as? JsonPrimitive)?.longOrNull?.minus(clock.wall)
        return null
    }
    fun timedOut(input: String, metadata: JsonObject?, id: String, clock: Clock): Boolean {
        val remaining = remainingMillis(input, metadata, id, clock) ?: return false
        val timer = timers(input, metadata)[id] as? JsonObject
        val ack = timer?.get("ack")
        return remaining <= 0 && (ack == null || ack != answers(input, metadata)[id])
    }

    fun update(
        input: String, request: AskUserProtocol.Request, metadata: JsonObject?, draft: JsonObject,
        displayed: Set<String>, clock: Clock, revision: Long? = null,
    ): JsonObject {
        val old = state(input, metadata)
        if (revision != null && clock.boot >= 0 && (old?.get("revision_boot") as? JsonPrimitive)?.intOrNull == clock.boot &&
            ((old["revision"] as? JsonPrimitive)?.longOrNull ?: Long.MIN_VALUE) > revision) return requireNotNull(metadata)
        require(draft.toString().length <= AskUserProtocol.MAX_ANSWER_LENGTH) { "ask_user draft is too large" }
        val bounded = draft.filter { (id, value) ->
            val q = request.questions.firstOrNull { it.id == id } ?: return@filter false
            when (value) {
                is JsonNull -> false
                is JsonPrimitive -> value.content.length <= (q.maxLength ?: AskUserProtocol.MAX_TEXT_ANSWER_LENGTH)
                is JsonArray -> value.size <= AskUserProtocol.MAX_OPTIONS && value.all { it is JsonPrimitive && it.isString && it.content in q.options }
                else -> false
            }
        }
        val visible = AskUserProtocol.visibleAnswers(request, bounded).toMutableMap()
        val timers = timers(input, metadata).toMutableMap()
        request.questions.filter { it.hasConfirmationCountdown() }.forEach { q ->
            var timer = timers[q.id] as? JsonObject
            if (!AskUserProtocol.isQuestionVisible(q, visible)) {
                if (timer != null) timers[q.id] = JsonObject(timer - "ack")
                return@forEach
            }
            if (timer == null && q.id in displayed) {
                val legacyRemaining = remainingMillis(input, metadata, q.id, clock)
                val duration = legacyRemaining ?: ((q.timeoutSeconds ?: AskUserProtocol.DEFAULT_CONFIRM_TIMEOUT_SECONDS) * 1000L)
                timer = buildJsonObject { put("wall_deadline", clock.wall + duration); put("elapsed_deadline", clock.elapsed + duration); put("boot", clock.boot) }
            }
            if (timer != null) {
                val existing = timer
                val value = (visible[q.id] as? JsonPrimitive)?.booleanOrNull
                val remaining = if ((existing["boot"] as? JsonPrimitive)?.intOrNull == clock.boot && clock.boot >= 0)
                    (existing["elapsed_deadline"] as? JsonPrimitive)?.longOrNull?.minus(clock.elapsed)
                    else (existing["wall_deadline"] as? JsonPrimitive)?.longOrNull?.minus(clock.wall)
                if (value == null) timer = JsonObject(existing - "ack")
                if (value != null && (remaining ?: 0) > 0) timer = JsonObject(existing + ("ack" to JsonPrimitive(value)))
                else if ((remaining ?: 0) <= 0 && (value == null || existing["ack"] != JsonPrimitive(value))) {
                    visible[q.id] = JsonPrimitive(false)
                    timer = JsonObject(existing - "ack")
                }
                timers[q.id] = timer
            }
        }
        // Expiring a parent confirmation can hide dependent fields in the same update.
        val finalAnswers = AskUserProtocol.visibleAnswers(request, visible)
        timers.replaceAll { id, value ->
            val timer = value as? JsonObject
            if (timer != null && timer["ack"] != finalAnswers[id]) JsonObject(timer - "ack") else value
        }
        return buildJsonObject {
            metadata?.forEach { (key, value) -> if (key != KEY && key != AskUserProtocol.CONFIRM_DEADLINE_METADATA_KEY) put(key, value) }
            put(KEY, buildJsonObject {
                put("fingerprint", fingerprint(input)); put("answers", JsonObject(finalAnswers)); put("timers", JsonObject(timers))
                if (revision != null) { put("revision", revision); put("revision_boot", clock.boot) }
                else { old?.get("revision")?.let { put("revision", it) }; old?.get("revision_boot")?.let { put("revision_boot", it) } }
            })
        }
    }

    fun submit(input: String, request: AskUserProtocol.Request, metadata: JsonObject?, payload: String, clock: Clock): Result<String> = runCatching {
        // Validate provided fields before the draft filter. Invalid optional values must not
        // silently become omitted answers; missing required confirmations may expire below.
        val provided = AskUserProtocol.validateAnswer(
            request.copy(questions = request.questions.map { it.copy(required = false) }), payload,
        ).getOrThrow()
        val submitted = Json.parseToJsonElement(provided).jsonObject["answers"]!!.jsonObject
        val updated = update(input, request, metadata, submitted, emptySet(), clock)
        val normalizedAnswers = answers(input, updated)
        request.questions.filter { it.hasConfirmationCountdown() && AskUserProtocol.isQuestionVisible(it, normalizedAnswers) }.forEach { q ->
            require(normalizedAnswers[q.id] != JsonPrimitive(true) || remainingMillis(input, updated, q.id, clock) != null) { "Confirmation has not been displayed" }
        }
        AskUserProtocol.validateAnswer(request, buildJsonObject { put("answers", normalizedAnswers) }.toString()).getOrThrow()
    }
}
