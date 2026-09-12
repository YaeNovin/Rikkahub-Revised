package me.rerere.rikkahub.ui.pages.setting

import org.junit.Assert.assertEquals
import org.junit.Test

class AppearancePerformanceEstimateTest {
    @Test
    fun `lightweight surfaces report low impact`() {
        assertEquals(
            AppearancePerformanceImpact.LOW,
            estimateAppearancePerformanceImpact(
                supportsRealtimeBlur = true,
                globalBackgroundActive = true,
                globalBackgroundBlurred = false,
                gradientBackgroundAnimated = false,
                inputBlurActive = false,
                topBarBlurActive = false,
                navigationGlassActive = false,
                dockGlassActive = false,
                bubbleGlassActive = false,
                richContentTranslucent = true,
            )
        )
    }

    @Test
    fun `several enabled effects report medium impact`() {
        assertEquals(
            AppearancePerformanceImpact.MEDIUM,
            estimateAppearancePerformanceImpact(
                supportsRealtimeBlur = true,
                globalBackgroundActive = true,
                globalBackgroundBlurred = true,
                gradientBackgroundAnimated = false,
                inputBlurActive = true,
                topBarBlurActive = false,
                navigationGlassActive = false,
                dockGlassActive = false,
                bubbleGlassActive = false,
                richContentTranslucent = false,
            )
        )
    }

    @Test
    fun `combined animation and glass effects report high impact`() {
        assertEquals(
            AppearancePerformanceImpact.HIGH,
            estimateAppearancePerformanceImpact(
                supportsRealtimeBlur = true,
                globalBackgroundActive = true,
                globalBackgroundBlurred = true,
                gradientBackgroundAnimated = true,
                inputBlurActive = true,
                topBarBlurActive = true,
                navigationGlassActive = true,
                dockGlassActive = true,
                bubbleGlassActive = true,
                richContentTranslucent = true,
            )
        )
    }

    @Test
    fun `unsupported realtime blur does not inflate impact`() {
        assertEquals(
            AppearancePerformanceImpact.LOW,
            estimateAppearancePerformanceImpact(
                supportsRealtimeBlur = false,
                globalBackgroundActive = false,
                globalBackgroundBlurred = true,
                gradientBackgroundAnimated = false,
                inputBlurActive = true,
                topBarBlurActive = true,
                navigationGlassActive = true,
                dockGlassActive = true,
                bubbleGlassActive = true,
                richContentTranslucent = false,
            )
        )
    }
}
