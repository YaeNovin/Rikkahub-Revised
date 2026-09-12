package me.rerere.rikkahub

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.*
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class AskUserDraftPersistenceTest {
    @Test fun localDraftAndDeadlineSurviveDatabaseCloseAndReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "ask-user-regression-${Uuid.random()}.db"
        var database = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            val input = """{"questions":[{"id":"text","question":"Text"},{"id":"confirm","question":"Confirm?","selection_type":"confirm","timeout_seconds":30}]}"""
            val request = AskUserProtocol.parseRequest(input).getOrThrow()
            val clock = AskUserInteraction.Clock(100_000, 1000, 1)
            val metadata = AskUserInteraction.update(input, request, AskUserInteraction.initial(input, clock.wall),
                buildJsonObject { put("text", "Unsubmitted draft 0.1234") }, setOf("confirm"), clock)
            val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool(
                toolCallId = "call-draft", toolName = "ask_user", input = input, approvalState = ToolApprovalState.Pending, metadata = metadata)))
            val conversationId = Uuid.random().toString()
            database.conversationDao().insert(ConversationEntity(conversationId, Uuid.random().toString(), "test", "[]", 0, 0, "[]", false))
            database.messageNodeDao().insert(MessageNodeEntity("node", conversationId, 0, JsonInstant.encodeToString(listOf(message)), 0))
            database.close()
            database = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            val restored = JsonInstant.decodeFromString<List<UIMessage>>(database.messageNodeDao().getNodesOfConversation(conversationId).single().messages)
                .single().getTools().single()
            assertEquals("Unsubmitted draft 0.1234", AskUserInteraction.answers(input, restored.metadata)["text"]!!.jsonPrimitive.content)
            assertEquals(29_000L, AskUserInteraction.remainingMillis(input, restored.metadata, "confirm", clock.copy(wall = 999_000, elapsed = 2000)))
            assertTrue(restored.approvalState is ToolApprovalState.Pending)
            assertTrue(restored.output.isEmpty())
        } finally { database.close(); context.deleteDatabase(name) }
    }
}
