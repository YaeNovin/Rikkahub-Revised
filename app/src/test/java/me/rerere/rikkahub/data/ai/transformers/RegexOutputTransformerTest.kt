package me.rerere.rikkahub.data.ai.transformers

import kotlin.uuid.Uuid
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.rikkahub.data.model.replaceRegexes
import org.junit.Assert.assertEquals
import org.junit.Test

class RegexOutputTransformerTest {
    @Test
    fun `generation finish applies actual assistant rules but not visual-only rules`() {
        val assistant = Assistant(
            regexes = listOf(
                AssistantRegex(
                    id = Uuid.random(),
                    findRegex = "secret",
                    replaceString = "[hidden]",
                    affectingScope = setOf(AssistantAffectScope.ASSISTANT),
                ),
                AssistantRegex(
                    id = Uuid.random(),
                    findRegex = "visible",
                    replaceString = "styled",
                    affectingScope = setOf(AssistantAffectScope.ASSISTANT),
                    visualOnly = true,
                ),
            )
        )

        val transformed = transformActualAssistantContent(
            assistant = assistant,
            messages = listOf(
                UIMessage.user("secret"),
                UIMessage.assistant("historical secret"),
                UIMessage.assistant("secret visible"),
            ),
        )

        assertEquals("secret", transformed[0].toText())
        assertEquals("historical secret", transformed[1].toText())
        assertEquals("[hidden] visible", transformed[2].toText())
    }

    @Test
    fun `regex rules apply in declared order and respect scope`() {
        val assistant = Assistant(
            regexes = listOf(
                AssistantRegex(
                    id = Uuid.random(),
                    findRegex = "foo",
                    replaceString = "bar",
                    affectingScope = setOf(AssistantAffectScope.USER),
                ),
                AssistantRegex(
                    id = Uuid.random(),
                    findRegex = "bar",
                    replaceString = "baz",
                    affectingScope = setOf(AssistantAffectScope.USER),
                ),
            )
        )

        assertEquals("baz", "foo".replaceRegexes(assistant, AssistantAffectScope.USER))
        assertEquals("foo", "foo".replaceRegexes(assistant, AssistantAffectScope.ASSISTANT))
    }

    @Test
    fun `malformed legacy rules are skipped without interrupting output`() {
        val assistant = Assistant(
            regexes = listOf(
                AssistantRegex(
                    id = Uuid.random(),
                    findRegex = "[",
                    replaceString = "broken",
                    affectingScope = setOf(AssistantAffectScope.ASSISTANT),
                ),
                AssistantRegex(
                    id = Uuid.random(),
                    findRegex = "(ok)",
                    replaceString = "\$2",
                    affectingScope = setOf(AssistantAffectScope.ASSISTANT),
                ),
            )
        )

        val transformed = runCatching {
            "ok".replaceRegexes(assistant, AssistantAffectScope.ASSISTANT)
        }.getOrNull()

        assertEquals("ok", transformed)
    }
}
