package me.rerere.rikkahub.data.datastore

import org.junit.Assert.assertEquals
import org.junit.Test

class ExtensionManagementModeTest {
    @Test
    fun `unknown and missing values fall back to normal mode`() {
        assertEquals(ExtensionManagementMode.NORMAL, decodeExtensionManagementMode(null))
        assertEquals(ExtensionManagementMode.NORMAL, decodeExtensionManagementMode("UNKNOWN"))
    }

    @Test
    fun `saved entertainment mode is restored`() {
        assertEquals(
            ExtensionManagementMode.ENTERTAINMENT,
            decodeExtensionManagementMode(ExtensionManagementMode.ENTERTAINMENT.name),
        )
    }

    @Test
    fun `quick message sort mode restores and falls back safely`() {
        assertEquals(QuickMessageSortMode.DEFAULT, decodeQuickMessageSortMode(null))
        assertEquals(QuickMessageSortMode.DEFAULT, decodeQuickMessageSortMode("UNKNOWN"))
        assertEquals(
            QuickMessageSortMode.FREQUENT,
            decodeQuickMessageSortMode(QuickMessageSortMode.FREQUENT.name),
        )
    }
}
