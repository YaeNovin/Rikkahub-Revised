package me.rerere.rikkahub.data.model

import java.security.MessageDigest

/** Facts retain their assistant/global scope; events require an explicit owning conversation. */
fun AssistantMemory.isVisibleInConversation(conversationId: String?): Boolean =
    if (scopeType != null) {
        when (scopeType) {
            MemoryScopeType.CONVERSATION -> !conversationId.isNullOrBlank() && scopeId == conversationId
            MemoryScopeType.ASSISTANT, MemoryScopeType.GLOBAL -> type == MemoryType.FACT
            MemoryScopeType.UNASSIGNED -> false
        }
    } else type == MemoryType.FACT || (!conversationId.isNullOrBlank() && sourceConversationId == conversationId)

fun memoryContentHash(content: String): String = MessageDigest.getInstance("SHA-256")
    .digest(content.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
