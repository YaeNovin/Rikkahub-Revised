package me.rerere.rikkahub.data.ai.transforms

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import org.junit.Assert.assertEquals
import org.junit.Test

class KnowledgeCitationRequestTest {
    @Test
    fun `only citations injected for the current request are returned`() {
        val historical = citation("historical")
        val current = citation("current")
        val messages = listOf(
            UIMessage.assistant("Previous reply").copy(annotations = listOf(historical)),
            UIMessage.system("$KNOWLEDGE_CONTEXT_START_TAG\nCurrent retrieval").copy(annotations = listOf(current)),
            UIMessage.user("New question"),
        )

        assertEquals(listOf(current), messages.currentRequestKnowledgeCitations())
    }

    @Test
    fun `historical citations are ignored when retrieval is disabled`() {
        val messages = listOf(
            UIMessage.assistant("Previous reply").copy(annotations = listOf(citation("historical"))),
            UIMessage.user("New question"),
        )

        assertEquals(emptyList<UIMessageAnnotation.KnowledgeCitation>(), messages.currentRequestKnowledgeCitations())
    }

    private fun citation(chunkId: String) = UIMessageAnnotation.KnowledgeCitation(
        chunkId = chunkId,
        knowledgeBaseId = "base",
        documentId = "document",
        title = "Source",
        sourceUri = "file:///source",
        excerpt = "Excerpt",
        score = 1f,
    )
}
