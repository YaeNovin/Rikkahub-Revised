package me.rerere.rikkahub.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import me.rerere.rikkahub.data.model.GradientBackgroundPreset
import me.rerere.rikkahub.data.model.GradientBackgroundCustomColors

@Immutable
internal data class GradientBlobColor(
    val color: Color,
    val alpha: Float,
)

@Immutable
internal data class GradientBackgroundPalette(
    val baseStops: List<Pair<Float, Color>>,
    val blobs: List<GradientBlobColor>,
)

internal fun updateGradientCustomColor(
    customColors: GradientBackgroundCustomColors,
    defaultPalette: GradientBackgroundPalette,
    editingBlob: Boolean,
    selectedIndex: Int,
    color: Color,
): GradientBackgroundCustomColors {
    val fallbackColors = if (editingBlob) {
        defaultPalette.blobs.map { it.color.toStoredArgb() }
    } else {
        defaultPalette.baseStops.map { it.second.toStoredArgb() }
    }
    if (fallbackColors.isEmpty()) return customColors

    val targetColors = if (editingBlob) customColors.blobColors else customColors.baseColors
    val updatedColors = fallbackColors.mapIndexed { index, fallback ->
        targetColors.getOrNull(index) ?: fallback
    }.toMutableList()
    updatedColors[selectedIndex.coerceIn(updatedColors.indices)] = color.toStoredArgb()

    return if (editingBlob) {
        customColors.copy(blobColors = updatedColors)
    } else {
        customColors.copy(baseColors = updatedColors)
    }
}

internal fun createGradientBackgroundPalette(
    colorScheme: ColorScheme,
    dark: Boolean,
    followTheme: Boolean,
    preset: GradientBackgroundPreset = GradientBackgroundPreset.CLASSIC,
    customColors: GradientBackgroundCustomColors = GradientBackgroundCustomColors(),
): GradientBackgroundPalette {
    if (!followTheme) {
        return fixedGradientBackgroundPalette(dark, preset)
            .withCustomColors(customColors)
            .withEnhancedLayerContrast()
    }

    val background = colorScheme.background.copy(alpha = 1f)
    val top = lerp(background, colorScheme.primaryContainer.copy(alpha = 1f), if (dark) 0.58f else 0.72f)
    val middle = lerp(background, colorScheme.secondaryContainer.copy(alpha = 1f), if (dark) 0.24f else 0.34f)
    val lower = lerp(background, colorScheme.surface.copy(alpha = 1f), 0.72f)
    return GradientBackgroundPalette(
        baseStops = listOf(
            0f to top,
            0.24f to lerp(top, middle, 0.54f),
            0.48f to middle,
            0.70f to lower,
            1f to background,
        ),
        blobs = listOf(
            GradientBlobColor(colorScheme.primary, if (dark) 0.54f else 0.66f),
            GradientBlobColor(colorScheme.secondary, if (dark) 0.42f else 0.52f),
            GradientBlobColor(colorScheme.tertiary, if (dark) 0.46f else 0.58f),
            GradientBlobColor(colorScheme.tertiaryContainer, if (dark) 0.30f else 0.40f),
        ),
    ).withCustomColors(customColors).withEnhancedLayerContrast()
}

private fun GradientBackgroundPalette.withCustomColors(
    customColors: GradientBackgroundCustomColors,
): GradientBackgroundPalette {
    if (customColors.baseColors.isEmpty() && customColors.blobColors.isEmpty()) return this
    val customBaseStops = baseStops.mapIndexed { index, (position, color) ->
        customColors.baseColors.getOrNull(index)?.let { argb ->
            position to Color(argb.toInt())
        } ?: (position to color)
    }
    val customBlobs = blobs.mapIndexed { index, blob ->
        customColors.blobColors.getOrNull(index)?.let { argb ->
            blob.copy(color = Color(argb.toInt()))
        } ?: blob
    }
    return copy(baseStops = customBaseStops, blobs = customBlobs)
}

private fun GradientBackgroundPalette.withEnhancedLayerContrast(): GradientBackgroundPalette =
    copy(
        blobs = blobs.map { blob ->
            blob.copy(alpha = (blob.alpha * 1.06f + 0.015f).coerceAtMost(0.84f))
        }
    )

private fun Color.toStoredArgb(): Long = toArgb().toLong() and 0xFFFF_FFFFL

internal fun gradientReadabilitySamples(
    palette: GradientBackgroundPalette,
    opacity: Float,
    intensity: Float,
    baseColor: Color,
): List<Color> {
    val safeOpacity = opacity.coerceIn(0f, 1f)
    val safeIntensity = intensity.coerceIn(0.5f, 1.5f)
    val opaqueBase = baseColor.copy(alpha = 1f)
    return buildList {
        palette.baseStops.forEach { (_, stopColor) ->
            val baseSample = stopColor.copy(alpha = safeOpacity).compositeOver(opaqueBase)
            add(baseSample)
            palette.blobs.forEach { blob ->
                val blobComposite = blob.color.copy(
                    alpha = (blob.alpha * safeIntensity).coerceIn(0f, 0.88f),
                ).compositeOver(stopColor.copy(alpha = 1f))
                add(blobComposite.copy(alpha = safeOpacity).compositeOver(opaqueBase))
            }
        }
    }
}

private fun fixedGradientBackgroundPalette(
    dark: Boolean,
    preset: GradientBackgroundPreset,
): GradientBackgroundPalette {
    if (preset == GradientBackgroundPreset.CLASSIC) {
        return if (dark) {
        GradientBackgroundPalette(
            baseStops = listOf(
                0f to Color(0xFF1B2A45),
                0.22f to Color(0xFF15223A),
                0.45f to Color(0xFF0D1626),
                0.65f to Color(0xFF0A0F18),
                1f to Color(0xFF080B12),
            ),
            blobs = listOf(
                GradientBlobColor(Color(0xFF3E6FB0), 0.56f),
                GradientBlobColor(Color(0xFF2E7D74), 0.44f),
                GradientBlobColor(Color(0xFF4A6E96), 0.48f),
                GradientBlobColor(Color(0xFF7C5F9E), 0.32f),
            ),
        )
        } else {
        GradientBackgroundPalette(
            baseStops = listOf(
                0f to Color(0xFFAFD0F2),
                0.22f to Color(0xFFCBE0F6),
                0.45f to Color(0xFFF1F7FD),
                0.65f to Color.White,
                1f to Color.White,
            ),
            blobs = listOf(
                GradientBlobColor(Color(0xFF9EC5F0), 0.72f),
                GradientBlobColor(Color(0xFFA8E6E0), 0.56f),
                GradientBlobColor(Color(0xFFB6D7F2), 0.62f),
                GradientBlobColor(Color(0xFFFFC8D2), 0.42f),
            ),
        )
        }
    }

    val colors = when (preset) {
        GradientBackgroundPreset.AURORA -> if (dark) {
            listOf(Color(0xFF0E4D4A), Color(0xFF246B5A), Color(0xFF3E7D68), Color(0xFF4A8D9A))
        } else {
            listOf(Color(0xFFBFEDE1), Color(0xFFD4F5E7), Color(0xFFE8FAF1), Color(0xFFBEE5EE))
        }

        GradientBackgroundPreset.SUNSET -> if (dark) {
            listOf(Color(0xFF4C234A), Color(0xFF713449), Color(0xFF8A453D), Color(0xFF9E5B45))
        } else {
            listOf(Color(0xFFFFD1B3), Color(0xFFFFE0C7), Color(0xFFFFEEF0), Color(0xFFFFC6D3))
        }

        GradientBackgroundPreset.MONOCHROME -> if (dark) {
            listOf(Color(0xFF59616B), Color(0xFF454C54), Color(0xFF343A40), Color(0xFF707780))
        } else {
            listOf(Color(0xFFD7DCE1), Color(0xFFE5E8EB), Color(0xFFF1F2F3), Color(0xFFC8CDD2))
        }

        GradientBackgroundPreset.CLASSIC -> error("Classic is handled above")
    }
    val base = if (dark) Color(0xFF10151A) else Color(0xFFF8FAFB)
    return GradientBackgroundPalette(
        baseStops = listOf(
            0f to colors[0],
            0.22f to colors[1],
            0.45f to colors[2],
            0.70f to lerp(colors[2], base, 0.55f),
            1f to base,
        ),
        blobs = listOf(
            GradientBlobColor(colors[0], if (dark) 0.58f else 0.66f),
            GradientBlobColor(colors[1], if (dark) 0.46f else 0.54f),
            GradientBlobColor(colors[2], if (dark) 0.48f else 0.58f),
            GradientBlobColor(colors[3], if (dark) 0.34f else 0.44f),
        ),
    )
}
