package me.rerere.rikkahub.ui.context

import androidx.navigation3.runtime.NavKey
import me.rerere.rikkahub.Screen
import org.junit.Assert.assertEquals
import org.junit.Test

class NavigatorTest {
    @Test
    fun `clear and navigate leaves only the target screen`() {
        val firstChat = Screen.Chat(id = "first")
        val settings = Screen.Setting
        val targetChat = Screen.Chat(id = "target")
        val backStack = mutableListOf<NavKey>(firstChat, settings)

        Navigator(backStack).clearAndNavigate(targetChat)

        assertEquals(listOf(targetChat), backStack)
    }
}
