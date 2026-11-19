package me.rerere.ai.provider.providers.google

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.providerRequestFailure
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.util.json
import me.rerere.common.http.awaitAndUse
import okhttp3.*
import okhttp3.sse.*

/** The caller supplies the same auth, request logging and headers as the Google provider. */
internal class GoogleInteractionsAPI(
    private val client: OkHttpClient,
    private val request: suspend (JsonObject) -> Request,
) {
    suspend fun generate(body: JsonObject): TextGenerationResult {
        var current = body
        var combined: TextGenerationResult? = null
        val tokens = mutableSetOf<String>()
        do {
            val response = client.newCall(request(current)).awaitAndUse { response ->
                if (!response.isSuccessful) throw providerRequestFailure(response, null,
                    response.body?.string().orEmpty())
                json.parseToJsonElement(response.body!!.string()).jsonObject
            }
            val result = parseGoogleInteraction(response)
            combined = combined?.let { previous -> result.copy(
                message = result.message.copy(id = previous.message.id,
                    parts = previous.message.parts + result.message.parts,
                    annotations = (previous.message.annotations + result.message.annotations).distinct()),
                usage = addGoogleHopUsage(previous.usage, result.usage),
            ) } ?: result
            val token = if (response.string("status") == "requires_continuation") response.string("continuation_token") else null
            if (response.string("status") == "requires_continuation") require(!token.isNullOrBlank()) {
                "Interactions continuation token missing"
            }
            if (token == null) break
            require(tokens.size < 64 && tokens.add(token)) { "Interactions repeated its continuation checkpoint" }
            current = interactionContinuation(current, response["steps"] as? JsonArray ?: JsonArray(emptyList()), token)
        } while (true)
        return checkNotNull(combined)
    }

    fun stream(body: JsonObject): Flow<StreamChunk> = flow {
        var current = body
        val tokens = mutableSetOf<String>()
        var totalUsage: me.rerere.ai.core.TokenUsage? = null
        do {
            val decoder = GoogleInteractionsStreamDecoder()
            var hopUsage: me.rerere.ai.core.TokenUsage? = null
            streamHop(request(current), decoder).collect { chunk ->
                when {
                    chunk is StreamChunk.Finish && chunk.finishReason == "requires_continuation" -> Unit
                    chunk is StreamChunk.Usage -> {
                        hopUsage = chunk.usage
                        emit(StreamChunk.Usage(checkNotNull(addGoogleHopUsage(totalUsage, hopUsage))))
                    }
                    else -> emit(chunk)
                }
            }
            totalUsage = addGoogleHopUsage(totalUsage, hopUsage)
            val token = decoder.continuationToken.takeIf { decoder.status == "requires_continuation" } ?: break
            require(tokens.size < 64 && tokens.add(token)) { "Interactions repeated its continuation checkpoint" }
            current = interactionContinuation(current, JsonArray(decoder.steps), token)
        } while (true)
    }

    private fun streamHop(request: Request, decoder: GoogleInteractionsStreamDecoder): Flow<StreamChunk> = callbackFlow {
        val source = EventSources.createFactory(client).newEventSource(request, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                try {
                    val result = decoder.accept(SseEvent(id, type, data))
                    result.chunks.forEach { trySend(it) }
                    if (result.completed) close()
                } catch (error: Throwable) { close(error) }
            }

            override fun onClosed(eventSource: EventSource) {
                try { decoder.onClosed().forEach { trySend(it) }; close() }
                catch (error: Throwable) { close(error) }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val detail = runCatching { response?.body?.string() }.getOrNull()
                close(providerRequestFailure(response, t, detail))
            }
        })
        awaitClose { source.cancel() }
    }.buffer(Channel.UNLIMITED)
}

/** Stateless continuation replays all prior steps and never depends on a stored remote interaction. */
internal fun interactionContinuation(body: JsonObject, steps: JsonArray, token: String): JsonObject =
    JsonObject(body + mapOf("input" to JsonArray((body["input"] as? JsonArray).orEmpty() + steps),
        "continuation_token" to JsonPrimitive(token)))
