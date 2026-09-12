package me.rerere.rikkahub.ui.components.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HctColorPickerTest {
    @Test
    fun `HEX draft trims uppercases and adds hash`() {
        assertEquals("#12ABEF", normalizeHexDraft(" 12abef "))
        assertEquals("#12ABEF", normalizeHexDraft(" #12abef "))
        assertEquals("0X8012ABEF", normalizeHexDraft(" 0x8012abef "))
    }

    @Test
    fun `six digit HEX becomes opaque ARGB`() {
        val parsed = requireNotNull(parseUserHexColor(" 6750a4 "))

        assertEquals(0xFF6750A4.toInt(), parsed.argb)
        assertEquals("#6750A4", parsed.normalizedHex)
        assertFalse(parsed.alphaRemoved)
    }

    @Test
    fun `CSS eight digit HEX removes trailing alpha`() {
        val parsed = requireNotNull(parseUserHexColor("#6750A480"))

        assertEquals(0xFF6750A4.toInt(), parsed.argb)
        assertEquals("#6750A4", parsed.normalizedHex)
        assertTrue(parsed.alphaRemoved)
    }

    @Test
    fun `Android eight digit HEX removes leading alpha`() {
        val parsed = requireNotNull(parseUserHexColor("0x806750a4"))

        assertEquals(0xFF6750A4.toInt(), parsed.argb)
        assertEquals("#6750A4", parsed.normalizedHex)
        assertTrue(parsed.alphaRemoved)
    }

    @Test
    fun `invalid HEX is rejected`() {
        assertNull(parseUserHexColor("#12FG56"))
        assertNull(parseUserHexColor("#12345"))
        assertNull(parseUserHexColor(""))
    }

    @Test
    fun `formatting always excludes stored alpha`() {
        assertEquals("#123ABC", formatOpaqueHexColor(0x40123ABC))
    }
}
