package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.hazeBlur
import me.rerere.rikkahub.data.datastore.BackgroundSurfaceStyle
import me.rerere.rikkahub.ui.theme.LocalBaseThemeColorScheme
import me.rerere.rikkahub.ui.theme.LocalBackgroundBaseColorScheme
import me.rerere.rikkahub.ui.theme.contrastingTextColor
import me.rerere.rikkahub.utils.getActivity
import kotlin.math.roundToInt

private val acrylicGrain: ImageBitmap by lazy {
    val random = kotlin.random.Random(7183)
    val pixels = IntArray(64 * 64) { if (random.nextBoolean()) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
    android.graphics.Bitmap.createBitmap(pixels, 64, 64, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** A single tint + static fine grain. Foreground content never participates in blur. */
@Composable
internal fun AcrylicSurface(
    opacity: Float,
    blurRadius: Float,
    effectsEnabled: Boolean,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    scene: HazeState? = null,
    snapshotOverlay: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val caps = LocalAdvancedAppearanceCapabilities.current
    val saving = LocalGlassPowerSave.current
    val view = LocalView.current
    // View properties are not Compose state. Initial composition can precede
    // attachment; refresh after layout instead of keeping a false hardware flag.
    var hardwareAccelerated by remember(view) { mutableStateOf(view.isHardwareAccelerated) }
    val activityWindow = LocalContext.current.getActivity()?.window
    // A same-window panel would capture itself. Only separate dialogs/popups sample the activity.
    val separateWindow = view.rootView !== activityWindow?.decorView
    val capture = snapshotOverlay && separateWindow && effectsEnabled && !saving &&
        caps.supportsRealtimeBlur && hardwareAccelerated && opacity < 1f
    val snapshot = rememberAcrylicSceneSnapshot(capture)
    val sourceAvailable = scene != null || snapshot != null
    val plan = resolveMaterialSurface(BackgroundSurfaceStyle.ACRYLIC, opacity, blurRadius,
        effectsEnabled, caps.supportsRealtimeBlur && hardwareAccelerated, sourceAvailable,
        if (LocalGlassBusy.current) minOf(8f, caps.maxLiveBlurRadius) else caps.maxLiveBlurRadius, saving)
    val scheme = LocalBackgroundBaseColorScheme.current ?: LocalBaseThemeColorScheme.current ?: MaterialTheme.colorScheme
    val tintColor = lerp(scheme.surfaceContainerLow.copy(alpha = 1f), scheme.primary.copy(alpha = 1f), .065f)
    val tint = tintColor.copy(alpha = plan.opacity)
    val fallback = scheme.surfaceContainerLow.copy(alpha = 1f)
    val backgrounds = if (snapshotOverlay && snapshot == null) listOf(tint.compositeOver(fallback)) else
        (snapshot?.samples ?: LocalAppearanceBackground.current?.readability?.backgrounds ?: listOf(scheme.background.copy(alpha = 1f)))
            .map { tint.compositeOver(it) }
    val textSeed = me.rerere.rikkahub.ui.theme.currentTextPaletteSeed()
    val foreground = remember(scheme.onSurface, backgrounds, textSeed) {
        if (textSeed != null) me.rerere.rikkahub.ui.theme.textForegroundFor(backgrounds, textSeed, scheme.onSurface)
        else contrastingTextColor(scheme.onSurface, backgrounds)
    }
    val foregroundVariant = remember(scheme.onSurfaceVariant, backgrounds, textSeed) {
        if (textSeed != null) me.rerere.rikkahub.ui.theme.textForegroundFor(backgrounds, textSeed, scheme.onSurfaceVariant)
        else contrastingTextColor(scheme.onSurfaceVariant, backgrounds)
    }
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(modifier.clip(shape).onGloballyPositioned {
        origin = it.positionOnScreen()
        hardwareAccelerated = view.isHardwareAccelerated
    }) {
        if (snapshotOverlay) {
            Box(Modifier.matchParentSize().background(fallback))
            if (snapshot != null) Canvas(Modifier.matchParentSize().then(
                if (plan.blurRadius > 0f) Modifier.blur(plan.blurRadius.dp, BlurredEdgeTreatment.Rectangle) else Modifier
            )) {
                drawImage(snapshot.image,
                    srcSize = IntSize(snapshot.image.width, snapshot.image.height),
                    dstOffset = IntOffset((snapshot.origin.x - origin.x).roundToInt(), (snapshot.origin.y - origin.y).roundToInt()),
                    dstSize = snapshot.size)
            }
        } else if (scene != null && plan.blurRadius > 0f) {
            Box(Modifier.matchParentSize().hazeBlur(input = HazeInput.Sources(scene), style = backgroundOnlyBlurStyle(plan.blurRadius)))
        }
        Box(Modifier.matchParentSize().background(tint).then(if (plan.grainEnabled) Modifier.drawWithCache {
            val brush = ShaderBrush(ImageShader(acrylicGrain, TileMode.Repeated, TileMode.Repeated))
            onDrawBehind { drawRect(brush, alpha = .018f * plan.opacity) }
        } else Modifier).border(1.dp, scheme.outlineVariant.copy(alpha = .32f * plan.opacity), shape))
        MaterialTheme(colorScheme = scheme.copy(onSurface = foreground, onSurfaceVariant = foregroundVariant)) {
            CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides foreground,
                LocalInsideGlassSurface provides true,
                me.rerere.rikkahub.ui.components.richtext.LocalTextSurfaceBackgrounds provides backgrounds,
                me.rerere.rikkahub.ui.theme.LocalChatBackgroundForeground provides foreground,
                LocalAppearanceBackground provides LocalAppearanceBackground.current.takeUnless { snapshotOverlay },
                LocalGlassBackdrop provides LocalGlassBackdrop.current.takeUnless { snapshotOverlay },
                LocalGlobalBackgroundHazeState provides LocalGlobalBackgroundHazeState.current.takeUnless { snapshotOverlay },
            ) { content() }
        }
    }
}
