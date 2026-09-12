package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.utils.JsonInstant

/** Apply only fields edited by this UI snapshot, retaining concurrent changes. */
internal fun mergeAssistantEdits(before: Assistant, edited: Assistant, current: Assistant): Assistant {
    require(before.id == edited.id && current.id == edited.id)
    val serializer = Assistant.serializer()
    val old = JsonInstant.encodeToJsonElement(serializer, before).jsonObject
    val draft = JsonInstant.encodeToJsonElement(serializer, edited).jsonObject
    val latest = JsonInstant.encodeToJsonElement(serializer, current).jsonObject.toMutableMap()
    (old.keys + draft.keys).filter { old[it] != draft[it] }.forEach { key ->
        if (key in draft) latest[key] = draft.getValue(key) else latest.remove(key)
    }
    return JsonInstant.decodeFromJsonElement(serializer, JsonObject(latest))
}
