package me.rerere.rikkahub.ui.components.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.darkColorScheme

class AppearanceSurfacePolicyTest {
    @Test
    fun `overlay opacity remains the requested visual tint`() {
        val transparent = isolatedSurfaceTintAlpha(0.35f)
        val opaque = isolatedSurfaceTintAlpha(1f)

        assertEquals(0.35f, transparent)
        assertEquals(1f, opaque)
        assertNotEquals(transparent, opaque)
    }

    @Test
    fun `isolated overlays always occlude page content behind them`() {
        val backing = isolatedSurfaceBackingColor(Color(0x20123456))

        assertEquals(1f, backing.alpha)
        assertEquals(Color(0xFF123456), backing)
    }

    @Test
    fun `page opacity remains responsive inside supported range`() {
        assertEquals(0.35f, normalizedPageSurfaceOpacity(0.1f))
        assertEquals(0.64f, normalizedPageSurfaceOpacity(0.64f))
        assertEquals(1f, normalizedPageSurfaceOpacity(2f))
    }

    @Test
    fun `liquid glass blur can be disabled without disabling optical layers`() {
        assertEquals(0f, liquidGlassBlurRadius(-4f))
        assertEquals(0f, liquidGlassBlurRadius(0f))
        assertEquals(20f, liquidGlassBlurRadius(20f))
        assertEquals(24f, liquidGlassBlurRadius(40f))
    }

    @Test
    fun `opaque overlays restore theme colors instead of image foreground colors`() {
        val themeScheme = darkColorScheme(
            surfaceContainerLow = Color(0xFF171717),
            onSurface = Color.White,
        )
        val imageReadableScheme = themeScheme.copy(onSurface = Color.Black)

        val opaque = resolveIsolatedSurfaceColorScheme(
            style = me.rerere.rikkahub.data.datastore.BackgroundSurfaceStyle.OPAQUE,
            renderedScheme = imageReadableScheme,
            backgroundBaseScheme = themeScheme,
        )
        val translucent = resolveIsolatedSurfaceColorScheme(
            style = me.rerere.rikkahub.data.datastore.BackgroundSurfaceStyle.TRANSLUCENT,
            renderedScheme = imageReadableScheme,
            backgroundBaseScheme = themeScheme,
        )

        assertEquals(Color.White, opaque.onSurface)
        assertEquals(Color.Black, translucent.onSurface)
    }
}
