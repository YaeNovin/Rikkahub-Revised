package me.rerere.rikkahub.data.files

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SkillScanTest {
    @Test
    fun `scan returns valid skills and reports malformed directories`() {
        val root = Files.createTempDirectory("skills-scan").toFile()
        try {
            root.resolve("valid").apply {
                mkdirs()
                resolve("SKILL.md").writeText(
                    """
                    ---
                    name: valid-skill
                    description: A valid skill
                    allowed-tools: search read
                    ---
                    Instructions
                    """.trimIndent()
                )
            }
            root.resolve("missing-manifest").mkdirs()
            root.resolve("missing-name").apply {
                mkdirs()
                resolve("SKILL.md").writeText("---\ndescription: Missing name\n---\nBody")
            }
            root.resolve("missing-description").apply {
                mkdirs()
                resolve("SKILL.md").writeText("---\nname: missing-description\n---\nBody")
            }
            root.resolve(".valid.staging.0.tmp").mkdirs()

            val result = scanSkillDirectory(root)

            assertEquals(listOf("valid-skill"), result.skills.map { it.name })
            assertEquals(listOf("search", "read"), result.skills.single().allowedTools)
            assertEquals(
                setOf(
                    SkillScanProblemKind.MISSING_MANIFEST,
                    SkillScanProblemKind.MISSING_NAME,
                    SkillScanProblemKind.MISSING_DESCRIPTION,
                ),
                result.problems.mapTo(hashSetOf()) { it.kind },
            )
            assertFalse(result.problems.any { it.directoryName == ".valid.staging.0.tmp" })
        } finally {
            root.deleteRecursively()
        }
    }
}
