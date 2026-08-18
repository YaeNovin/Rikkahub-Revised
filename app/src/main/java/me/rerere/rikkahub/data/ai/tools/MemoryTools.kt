package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryType
import me.rerere.rikkahub.utils.toLocalString
import java.time.LocalDate

private const val DEFAULT_LIST_LIMIT = 50
private const val MAX_LIST_LIMIT = 100

fun buildMemoryTools(
    json: Json,
    allowEpisodicMemory: Boolean = false,
    onCreation: suspend (String, MemoryType) -> AssistantMemory,
    onUpdate: suspend (Int, String) -> AssistantMemory?,
    onDelete: suspend (Int) -> Boolean,
    onList: suspend () -> List<AssistantMemory>,
): List<Tool> = listOf(
    Tool(
        name = "memory_tool",
        description = """
            The memory tool stores long-term information across conversations.
            Use `action` to control the operation: `create` (add), `edit` (update), `delete` (remove), `list` (read).
            - No relevant record: `create` + `content`
            - Existing relevant record: `edit` + `id` + `content`
            - Outdated/irrelevant record: `delete` + `id`
            - To inspect saved memories before changing one: `list`, optionally with `offset` and `limit`
            ${if (allowEpisodicMemory) "You may use type=episodic for a concrete event, decision, or experience from this conversation. Use type=fact for durable user preferences or profile information." else "Use type=fact. Episodic memory is disabled for this assistant."}
            Memories may be retrieved in later conversations when relevant.
            Do not store sensitive information (e.g., ethnicity, religion, sexual orientation, political views, sex life, criminal records).
            You may store: preferred name, preferences, plans, work-related notes, chat style preferences, first chat time, etc.
            Do not show memory content directly in the conversation unless the user explicitly asks.
            Today is ${LocalDate.now().toLocalString(true)}.
            Similar memories should be merged; prefer updating existing records.

            Examples:
            {"action":"create","content":"User prefers brief replies and is more active on weekends."}
            {"action":"edit","id":12,"content":"User’s preferred name updated to “A-Xing”, prefers Chinese replies."}
            {"action":"delete","id":7}
            {"action":"list","offset":0,"limit":50}
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                add("create")
                                add("edit")
                                add("delete")
                                add("list")
                            }
                        )
                        put("description", "Operation to perform: create, edit, or delete")
                    })
                    put("id", buildJsonObject {
                        put("type", "integer")
                        put("description", "The id of the memory record (required for edit/delete)")
                    })
                    put("content", buildJsonObject {
                        put("type", "string")
                        put("description", "The content of the memory record (required for create/edit)")
                    })
                    put("type", buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                add("fact")
                                if (allowEpisodicMemory) add("episodic")
                            }
                        )
                        put("description", "Memory category for create: fact or episodic")
                    })
                    put("offset", buildJsonObject {
                        put("type", "integer")
                        put("description", "Offset for list, defaults to 0")
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put("description", "Page size for list, from 1 to $MAX_LIST_LIMIT; defaults to $DEFAULT_LIST_LIMIT")
                    })
                },
                required = listOf("action")
            )
        },
        execute = {
            val params = it.jsonObject
            val action = params["action"]?.jsonPrimitive?.contentOrNull ?: error("action is required")
            val payload = when (action) {
                "create" -> {
                    val content = params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required")
                    val rawType = params["type"]?.jsonPrimitive?.contentOrNull ?: "fact"
                    val type = when (rawType.lowercase()) {
                        "fact" -> MemoryType.FACT
                        "episodic" -> {
                            check(allowEpisodicMemory) { "episodic memory is disabled" }
                            MemoryType.EPISODIC
                        }
                        else -> error("unknown memory type: $rawType")
                    }
                    buildJsonObject {
                        put("status", "created")
                        put("memory", json.encodeToJsonElement(AssistantMemory.serializer(), onCreation(content, type)))
                    }
                }

                "edit" -> {
                    val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
                    val content = params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required")
                    onUpdate(id, content)?.let { memory ->
                        buildJsonObject {
                            put("status", "updated")
                            put("memory", json.encodeToJsonElement(AssistantMemory.serializer(), memory))
                        }
                    } ?: buildJsonObject {
                        put("status", "not_found")
                        put("id", id)
                        put("action", "edit")
                    }
                }

                "delete" -> {
                    val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
                    if (onDelete(id)) {
                        buildJsonObject {
                            put("status", "deleted")
                            put("id", id)
                        }
                    } else buildJsonObject {
                        put("status", "not_found")
                        put("id", id)
                        put("action", "delete")
                    }
                }

                "list" -> {
                    val allMemories = onList()
                    val offset = (params["offset"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0)
                    val limit = (params["limit"]?.jsonPrimitive?.intOrNull ?: DEFAULT_LIST_LIMIT)
                        .coerceIn(1, MAX_LIST_LIMIT)
                    val page = allMemories.drop(offset).take(limit)
                    buildJsonObject {
                        put("status", "ok")
                        put("total", allMemories.size)
                        put("offset", offset)
                        put("limit", limit)
                        put("hasMore", offset + page.size < allMemories.size)
                        put("memories", json.encodeToJsonElement(ListSerializer(AssistantMemory.serializer()), page))
                    }
                }

                else -> error("unknown action: $action, must be one of [create, edit, delete, list]")
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )
)
