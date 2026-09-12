package me.rerere.rikkahub.ui.modifier

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

class ShimmerGeometryTest {
    @Test fun `shimmer enters before left edge and exits beyond right edge`() {
        assertEquals(-150f, shimmerBandOffset(200f, 80f, Offset(1f, 0f), 50f, 0f), .001f)
        assertEquals(100f, shimmerBandOffset(200f, 80f, Offset(1f, 0f), 50f, 1f), .001f)
        assertEquals(-25f, shimmerBandOffset(200f, 80f, Offset(1f, 0f), 50f, .5f), .001f)
    }
    @Test fun `vertical shimmer covers entire tall preview`() {
        assertEquals(-500f, shimmerBandOffset(80f, 800f, Offset(0f, 1f), 100f, 0f), .001f)
        assertEquals(400f, shimmerBandOffset(80f, 800f, Offset(0f, 1f), 100f, 1f), .001f)
    }
}
