package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.material3.DrawerValue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDrawerStateTest {
    @Test
    fun `drawer rendering stays busy throughout open and close transitions`() {
        assertFalse(drawerOccludesChat(DrawerValue.Closed, DrawerValue.Closed))
        assertTrue(drawerOccludesChat(DrawerValue.Closed, DrawerValue.Open))
        assertTrue(drawerOccludesChat(DrawerValue.Open, DrawerValue.Open))
        assertTrue(drawerOccludesChat(DrawerValue.Open, DrawerValue.Closed))
    }
}
