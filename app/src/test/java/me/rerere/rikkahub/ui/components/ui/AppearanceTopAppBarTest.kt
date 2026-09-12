package me.rerere.rikkahub.ui.components.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceTopAppBarTest {
    @Test
    fun `top bar blur requires every rendering prerequisite`() {
        assertTrue(
            shouldApplyTopBarBlur(
                backgroundActive = true,
                blurEnabled = true,
                performanceEffectsEnabled = true,
                blurSupported = true,
                hasHazeSource = true,
            )
        )
        assertFalse(
            shouldApplyTopBarBlur(
                backgroundActive = true,
                blurEnabled = false,
                performanceEffectsEnabled = true,
                blurSupported = true,
                hasHazeSource = true,
            )
        )
        assertFalse(
            shouldApplyTopBarBlur(
                backgroundActive = false,
                blurEnabled = true,
                performanceEffectsEnabled = true,
                blurSupported = true,
                hasHazeSource = true,
            )
        )
    }
}
