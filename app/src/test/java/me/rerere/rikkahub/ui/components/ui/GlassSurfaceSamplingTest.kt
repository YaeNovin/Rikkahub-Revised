package me.rerere.rikkahub.ui.components.ui

import org.junit.Assert.*
import org.junit.Test

class GlassSurfaceSamplingTest {
    @Test fun `ordinary translucent card exposes its existing parent instead of replaying wallpaper`() {
        assertFalse(shouldPaintGlassBackdrop(isolated = false, blurRadius = 0f, refractionActive = false))
    }
    @Test fun `only an actual effect needs a sampled background in an inline surface`() {
        assertTrue(shouldPaintGlassBackdrop(isolated = false, blurRadius = 8f, refractionActive = false))
        assertTrue(shouldPaintGlassBackdrop(isolated = false, blurRadius = 0f, refractionActive = true))
        // Quota exhaustion, motion reduction and shader failure all end at this fallback.
        assertFalse(shouldPaintGlassBackdrop(isolated = false, blurRadius = 0f, refractionActive = false))
    }
    @Test fun `isolated surface can retain configured background without exposing page text`() {
        assertTrue(shouldPaintGlassBackdrop(isolated = true, blurRadius = 0f, refractionActive = false))
    }
}
