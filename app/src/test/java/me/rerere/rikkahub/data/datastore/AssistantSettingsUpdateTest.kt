package me.rerere.rikkahub.data.datastore

import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.GradientBackgroundCustomColors
import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantSettingsUpdateTest {
    @Test
    fun `gradient update changes only the target assistant`() {
        val target = Assistant(name = "target")
        val other = Assistant(name = "other")
        val settings = Settings(assistants = listOf(target, other))
        val colors = GradientBackgroundCustomColors(baseColors = listOf(0xFF123456))

        val updated = settings.withUpdatedAssistant(target.id) { assistant ->
            assistant.copy(gradientBackgroundCustomColors = colors)
        }

        assertEquals(colors, updated.assistants[0].gradientBackgroundCustomColors)
        assertEquals(other, updated.assistants[1])
    }

    @Test
    fun `unknown assistant id leaves settings unchanged`() {
        val settings = Settings(assistants = listOf(Assistant(name = "existing")))

        val updated = settings.withUpdatedAssistant(Uuid.random()) { assistant ->
            assistant.copy(name = "unexpected")
        }

        assertEquals(settings, updated)
    }
}
