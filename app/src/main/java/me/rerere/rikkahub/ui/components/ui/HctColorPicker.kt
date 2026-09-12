package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt
import me.rerere.material3.hctColorFromArgb
import me.rerere.material3.hctColorFromComponents
import me.rerere.rikkahub.R

private const val OPAQUE_ALPHA = 0xFF000000.toInt()

@Composable
fun HctColorPicker(
    color: Color,
    onColorChange: (Color) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val sourceArgb = color.toArgb() or OPAQUE_ALPHA
    val initialHct = remember { hctColorFromArgb(sourceArgb) }
    var requestedHue by remember { mutableFloatStateOf(initialHct.hue.toFloat()) }
    var requestedChroma by remember { mutableFloatStateOf(initialHct.chroma.toFloat()) }
    var requestedTone by remember { mutableFloatStateOf(initialHct.tone.toFloat()) }
    var actualHct by remember { mutableStateOf(initialHct) }
    var hexDraft by remember { mutableStateOf(formatOpaqueHexColor(initialHct.argb)) }
    var hexError by remember { mutableStateOf(false) }
    var alphaRemoved by remember { mutableStateOf(false) }
    var lastEmittedArgb by remember { mutableIntStateOf(initialHct.argb) }

    fun applyExternalColor(nextArgb: Int) {
        val hct = hctColorFromArgb(nextArgb or OPAQUE_ALPHA)
        requestedHue = hct.hue.toFloat()
        requestedChroma = hct.chroma.toFloat()
        requestedTone = hct.tone.toFloat()
        actualHct = hct
        hexDraft = formatOpaqueHexColor(hct.argb)
        hexError = false
        alphaRemoved = false
        lastEmittedArgb = hct.argb
    }

    LaunchedEffect(sourceArgb) {
        if (sourceArgb != lastEmittedArgb) {
            applyExternalColor(sourceArgb)
        }
    }

    fun updateFromHct(hue: Float, chroma: Float, tone: Float) {
        requestedHue = hue
        requestedChroma = chroma
        requestedTone = tone
        val resolved = hctColorFromComponents(
            hue = hue.toDouble(),
            chroma = chroma.toDouble(),
            tone = tone.toDouble(),
        )
        actualHct = resolved
        hexDraft = formatOpaqueHexColor(resolved.argb)
        hexError = false
        alphaRemoved = false
        lastEmittedArgb = resolved.argb
        onColorChange(Color(resolved.argb))
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Canvas(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape),
            ) {
                drawCircle(Color(actualHct.argb))
            }
            Text(
                text = stringResource(
                    R.string.color_picker_actual_hct,
                    actualHct.hue.roundToInt(),
                    actualHct.chroma.roundToInt(),
                    actualHct.tone.roundToInt(),
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }

        HctSlider(
            label = stringResource(R.string.color_picker_hue),
            value = requestedHue,
            range = 0f..360f,
            enabled = enabled,
        ) { updateFromHct(it, requestedChroma, requestedTone) }
        HctSlider(
            label = stringResource(R.string.color_picker_chroma),
            value = requestedChroma,
            range = 0f..150f,
            enabled = enabled,
        ) { updateFromHct(requestedHue, it, requestedTone) }
        HctSlider(
            label = stringResource(R.string.color_picker_tone),
            value = requestedTone,
            range = 0f..100f,
            enabled = enabled,
        ) { updateFromHct(requestedHue, requestedChroma, it) }

        OutlinedTextField(
            value = hexDraft,
            onValueChange = { input ->
                hexDraft = normalizeHexDraft(input)
                val parsed = parseUserHexColor(input)
                hexError = parsed == null
                alphaRemoved = parsed?.alphaRemoved == true
                if (parsed != null) {
                    val hct = hctColorFromArgb(parsed.argb)
                    requestedHue = hct.hue.toFloat()
                    requestedChroma = hct.chroma.toFloat()
                    requestedTone = hct.tone.toFloat()
                    actualHct = hct
                    hexDraft = parsed.normalizedHex
                    lastEmittedArgb = hct.argb
                    onColorChange(Color(hct.argb))
                }
            },
            enabled = enabled,
            label = { Text("HEX") },
            placeholder = { Text("#6750A4") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = hexError,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                keyboardType = KeyboardType.Ascii,
            ),
            supportingText = when {
                hexError -> {{ Text(stringResource(R.string.color_picker_hex_format_hint)) }}
                alphaRemoved -> {{ Text(stringResource(R.string.color_picker_hex_alpha_removed)) }}
                else -> null
            },
        )
    }
}

@Composable
private fun HctSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(48.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value.roundToInt().toString(),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(32.dp),
        )
    }
}

internal data class ParsedHexColor(
    val argb: Int,
    val normalizedHex: String,
    val alphaRemoved: Boolean,
)

internal fun normalizeHexDraft(input: String): String {
    val trimmed = input.trim().uppercase(Locale.ROOT)
    if (trimmed.isEmpty()) return ""
    return when {
        trimmed.startsWith("#") -> trimmed
        trimmed.startsWith("0X") -> trimmed
        else -> "#$trimmed"
    }
}

internal fun parseUserHexColor(input: String): ParsedHexColor? {
    val normalizedDraft = normalizeHexDraft(input)
    val androidArgb = normalizedDraft.startsWith("0X")
    val digits = when {
        androidArgb -> normalizedDraft.removePrefix("0X")
        normalizedDraft.startsWith("#") -> normalizedDraft.removePrefix("#")
        else -> return null
    }
    if ((digits.length != 6 && digits.length != 8) || digits.any { it !in HEX_DIGITS }) {
        return null
    }

    val rgb = when {
        digits.length == 6 -> digits
        androidArgb -> digits.drop(2)
        else -> digits.take(6)
    }
    val argb = (0xFF000000L or rgb.toLong(16)).toInt()
    return ParsedHexColor(
        argb = argb,
        normalizedHex = formatOpaqueHexColor(argb),
        alphaRemoved = digits.length == 8,
    )
}

internal fun formatOpaqueHexColor(argb: Int): String =
    String.format(Locale.ROOT, "#%06X", argb and 0x00FFFFFF)

private const val HEX_DIGITS = "0123456789ABCDEF"
