package me.rerere.rikkahub.ui.theme

import org.junit.Assert.*
import org.junit.Test

class WallpaperRomCompatibilityTest {
    private val blue = 0xFF3269BB.toInt()
    private val pink = 0xFFB55C89.toInt()

    @Test fun `neutral primary allows secondary accent without borrowing an app background`() {
        assertEquals(blue, resolveWallpaperTextSeed(listOf(-1, blue, pink), null).argb)
        assertEquals(blue, resolveWallpaperTextSeed(listOf(0, blue), null).argb)
        assertNull(resolveWallpaperTextSeed(listOf(0, 0x00112233), null).argb)
        assertEquals(0xFF888888.toInt(), resolveWallpaperTextSeed(listOf(0xFF888888.toInt()), blue).argb)
    }

    @Test fun `missing ROM metadata falls back explicitly and does not turn transparent black into a seed`() {
        val fallback = resolveWallpaperTextSeed(emptyList(), blue)
        assertEquals(blue, fallback.argb)
        assertTrue(fallback.source.contains("系统动态配色"))
        assertNull(resolveWallpaperTextSeed(emptyList(), 0).argb)
        assertNull(resolveWallpaperTextSeed(emptyList(), null).argb)
    }

    @Test fun `provided original wins only until a known wallpaper identity changes`() {
        assertEquals(pink, resolveWallpaperTextSeed(listOf(blue), null, pink, 145, 145).argb)
        val changed = resolveWallpaperTextSeed(listOf(blue), null, pink, 145, 146)
        assertEquals(blue, changed.argb)
        assertTrue(changed.source.contains("失效"))
        assertEquals(pink, resolveWallpaperTextSeed(emptyList(), null, pink, null, -1).argb)
    }
}
