package me.rerere.rikkahub.ui.pages.extensions.skills

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class SkillImportLimitsTest {
    @Test fun `binary contents survive bounded reading at exact limit`() {
        val bytes = byteArrayOf(0, -1, -128, 13, 10)
        assertArrayEquals(bytes, bytes.inputStream().readSkillBytes(bytes.size))
    }
    @Test(expected = IllegalArgumentException::class)
    fun `oversize input is rejected without truncation`() {
        byteArrayOf(1, 2, 3).inputStream().readSkillBytes(2)
    }
}
