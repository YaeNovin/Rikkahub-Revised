package me.rerere.rikkahub.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.roundToInt

/** Apply once to root typography. Nested surface themes inherit, never multiply again. */
internal fun TextStyle.withReadabilityWeight(): TextStyle = copy(
    fontWeight = FontWeight(((fontWeight ?: FontWeight.Normal).weight * 1.10f).roundToInt().coerceIn(1, 1000)),
    shadow = null,
)

internal fun Typography.withReadabilityWeight(): Typography = copy(
    displayLarge = displayLarge.withReadabilityWeight(),
    displayMedium = displayMedium.withReadabilityWeight(),
    displaySmall = displaySmall.withReadabilityWeight(),
    headlineLarge = headlineLarge.withReadabilityWeight(),
    headlineMedium = headlineMedium.withReadabilityWeight(),
    headlineSmall = headlineSmall.withReadabilityWeight(),
    titleLarge = titleLarge.withReadabilityWeight(),
    titleMedium = titleMedium.withReadabilityWeight(),
    titleSmall = titleSmall.withReadabilityWeight(),
    bodyLarge = bodyLarge.withReadabilityWeight(),
    bodyMedium = bodyMedium.withReadabilityWeight(),
    bodySmall = bodySmall.withReadabilityWeight(),
    labelLarge = labelLarge.withReadabilityWeight(),
    labelMedium = labelMedium.withReadabilityWeight(),
    labelSmall = labelSmall.withReadabilityWeight(),
    displayLargeEmphasized = displayLargeEmphasized.withReadabilityWeight(),
    displayMediumEmphasized = displayMediumEmphasized.withReadabilityWeight(),
    displaySmallEmphasized = displaySmallEmphasized.withReadabilityWeight(),
    headlineLargeEmphasized = headlineLargeEmphasized.withReadabilityWeight(),
    headlineMediumEmphasized = headlineMediumEmphasized.withReadabilityWeight(),
    headlineSmallEmphasized = headlineSmallEmphasized.withReadabilityWeight(),
    titleLargeEmphasized = titleLargeEmphasized.withReadabilityWeight(),
    titleMediumEmphasized = titleMediumEmphasized.withReadabilityWeight(),
    titleSmallEmphasized = titleSmallEmphasized.withReadabilityWeight(),
    bodyLargeEmphasized = bodyLargeEmphasized.withReadabilityWeight(),
    bodyMediumEmphasized = bodyMediumEmphasized.withReadabilityWeight(),
    bodySmallEmphasized = bodySmallEmphasized.withReadabilityWeight(),
    labelLargeEmphasized = labelLargeEmphasized.withReadabilityWeight(),
    labelMediumEmphasized = labelMediumEmphasized.withReadabilityWeight(),
    labelSmallEmphasized = labelSmallEmphasized.withReadabilityWeight(),
)
