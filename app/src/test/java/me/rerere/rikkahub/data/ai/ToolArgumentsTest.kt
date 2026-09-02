package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ToolArgumentsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `blank and object arguments are accepted`() {
        assertEquals(emptySet<String>(), json.parseToolArguments("", "workspace_list_local_files").keys)
        assertEquals(
            "ls -la",
            json.parseToolArguments("""{"command":"ls -la"}""", "workspace_shell")
                ["command"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `malformed and non object arguments are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            json.parseToolArguments("{\"command\":", "workspace_shell")
        }
        assertThrows(IllegalArgumentException::class.java) {
            json.parseToolArguments("[\"ls\"]", "workspace_shell")
        }
        assertThrows(IllegalArgumentException::class.java) {
            json.parseToolArguments("\"ls\"", "workspace_shell")
        }
    }
}
