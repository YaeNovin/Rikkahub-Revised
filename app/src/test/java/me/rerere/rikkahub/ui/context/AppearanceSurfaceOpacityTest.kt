package me.rerere.rikkahub.ui.context

import org.junit.Assert.assertEquals
import org.junit.Test

class AppearanceSurfaceOpacityTest {
    @Test
    fun `surface opacity is clamped to a readable range`() {
        val policy = appearanceSurfaceOpacityPolicy(
            cardOpacity = 0.05f,
            topBarOpacity = 1.2f,
            inputOpacity = -1f,
            dockOpacity = 0.58f,
            cardBackgroundActive = true,
        )

        assertEquals(0.35f, policy.forRole(AppearanceSurfaceRole.CARD), 0f)
        assertEquals(1f, policy.forRole(AppearanceSurfaceRole.TOP_BAR), 0f)
        assertEquals(0f, policy.forRole(AppearanceSurfaceRole.INPUT), 0f)
        assertEquals(0f, policy.forRole(AppearanceSurfaceRole.DOCK), 0f)
    }

    @Test
    fun `cards and top bar are opaque without an active background`() {
        val policy = appearanceSurfaceOpacityPolicy(
            cardOpacity = 0.42f,
            topBarOpacity = 0.42f,
            inputOpacity = 0.55f,
            dockOpacity = 0.58f,
            cardBackgroundActive = false,
        )

        assertEquals(1f, policy.card, 0f)
        assertEquals(1f, policy.topBar, 0f)
        assertEquals(0.55f, policy.input, 0f)
        assertEquals(0.55f, policy.dock, 0f)
    }
}
