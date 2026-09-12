package me.rerere.rikkahub.ui.components.ui

import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import me.rerere.rikkahub.ui.theme.GradientBackgroundPalette

private const val TAG = "AgslGradientBackground"

internal object AgslGradientRuntime {
    var failed by mutableStateOf(false)
}

internal fun createAgslGradientRendererOrNull(): AgslGradientRenderer? {
    if (Build.VERSION.SDK_INT < AGSL_GRADIENT_MIN_SDK || AgslGradientRuntime.failed) return null
    return AgslGradientRendererApi33.createOrNull()
}

@RequiresApi(33)
private object AgslGradientRendererApi33 {
    fun createOrNull(): AgslGradientRenderer? = try {
        RuntimeShaderGradientRenderer(RuntimeShader(AGSL_GRADIENT_SHADER))
    } catch (exception: RuntimeException) {
        AgslGradientRuntime.failed = true
        Log.w(TAG, "Unable to compile the AGSL gradient; using Kotlin rendering", exception)
        null
    }
}

@RequiresApi(33)
private class RuntimeShaderGradientRenderer(
    private val shader: RuntimeShader,
) : AgslGradientRenderer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.shader = this@RuntimeShaderGradientRenderer.shader
    }
    private var lastWidth = Float.NaN
    private var lastHeight = Float.NaN
    private var lastOpacity = Float.NaN
    private var lastIntensity = Float.NaN
    private var lastMotionScale = Float.NaN
    private var lastSoftness = Float.NaN
    private var lastAngle = Float.NaN
    private var lastVignette = Float.NaN
    private var lastBlobCount = -1
    private var lastPalette: GradientBackgroundPalette? = null

    override val isUsable: Boolean get() = !AgslGradientRuntime.failed

    override fun draw(
        scope: DrawScope,
        elapsedMillis: Long,
        palette: GradientBackgroundPalette,
        opacity: Float,
        intensity: Float,
        motionScale: Float,
        blobCount: Int,
        softness: Float,
        angle: Float,
        vignette: Float,
    ): Boolean {
        if (!isUsable || scope.size.width <= 0f || scope.size.height <= 0f) return false
        return try {
            if (scope.size.width != lastWidth || scope.size.height != lastHeight) {
                shader.setFloatUniform("resolution", scope.size.width, scope.size.height)
                val layout = resolveGradientLayoutProfile(scope.size.width, scope.size.height)
                shader.setFloatUniform(
                    "layoutProfile",
                    layout.verticalShift,
                    layout.radiusScale,
                    layout.horizontalMotionScale,
                    layout.verticalMotionScale,
                )
                lastWidth = scope.size.width
                lastHeight = scope.size.height
            }
            shader.setFloatUniform("phasesX", gradientMotionPhase(elapsedMillis, 5500), gradientMotionPhase(elapsedMillis, 7000), gradientMotionPhase(elapsedMillis, 8500), gradientMotionPhase(elapsedMillis, 6200))
            shader.setFloatUniform("phasesY", gradientMotionPhase(elapsedMillis, 5500, 1.15), gradientMotionPhase(elapsedMillis, 7000), gradientMotionPhase(elapsedMillis, 8500, .9), gradientMotionPhase(elapsedMillis, 6200, 1.1))
            if (opacity != lastOpacity) {
                shader.setFloatUniform("opacity", opacity)
                lastOpacity = opacity
            }
            if (intensity != lastIntensity) {
                shader.setFloatUniform("intensity", intensity)
                lastIntensity = intensity
            }
            if (motionScale != lastMotionScale) {
                shader.setFloatUniform("motionScale", motionScale)
                lastMotionScale = motionScale
            }
            if (softness != lastSoftness) {
                shader.setFloatUniform("softness", softness)
                lastSoftness = softness
            }
            if (angle != lastAngle) {
                shader.setFloatUniform("gradientAngle", angle)
                lastAngle = angle
            }
            if (vignette != lastVignette) {
                shader.setFloatUniform("vignette", vignette)
                lastVignette = vignette
            }
            if (blobCount != lastBlobCount) {
                shader.setFloatUniform("blobCount", blobCount.toFloat())
                lastBlobCount = blobCount
            }

            if (palette != lastPalette) {
                val stops = palette.baseStops
                setColorUniform("base0", stops.getOrElse(0) { 0f to Color.Transparent }.second)
                setColorUniform("base1", stops.getOrElse(1) { 0.24f to Color.Transparent }.second)
                setColorUniform("base2", stops.getOrElse(2) { 0.48f to Color.Transparent }.second)
                setColorUniform("base3", stops.getOrElse(3) { 0.70f to Color.Transparent }.second)
                setColorUniform("base4", stops.getOrElse(4) { 1f to Color.Transparent }.second)
                shader.setFloatUniform("stop1", stops.getOrElse(1) { 0.24f to Color.Transparent }.first)
                shader.setFloatUniform("stop2", stops.getOrElse(2) { 0.48f to Color.Transparent }.first)
                shader.setFloatUniform("stop3", stops.getOrElse(3) { 0.70f to Color.Transparent }.first)

                repeat(4) { index ->
                    val blob = palette.blobs.getOrNull(index)
                    setColorUniform(
                        name = "blob$index",
                        color = blob?.color ?: Color.Transparent,
                        alpha = blob?.alpha ?: 0f,
                    )
                }
                lastPalette = palette
            }

            with(scope) {
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint)
                }
            }
            true
        } catch (exception: RuntimeException) {
            AgslGradientRuntime.failed = true
            Log.w(TAG, "AGSL gradient rendering failed; using Kotlin rendering", exception)
            false
        }
    }

    private fun setColorUniform(
        name: String,
        color: Color,
        alpha: Float = color.alpha,
    ) {
        shader.setFloatUniform(name, color.red, color.green, color.blue, alpha)
    }
}

private const val AGSL_GRADIENT_SHADER = """
    uniform float2 resolution;
    uniform float4 phasesX;
    uniform float4 phasesY;
    uniform float opacity;
    uniform float intensity;
    uniform float motionScale;
    uniform float blobCount;
    uniform float softness;
    uniform float gradientAngle;
    uniform float vignette;
    uniform float4 layoutProfile;

    uniform float4 base0;
    uniform float4 base1;
    uniform float4 base2;
    uniform float4 base3;
    uniform float4 base4;
    uniform float stop1;
    uniform float stop2;
    uniform float stop3;

    uniform float4 blob0;
    uniform float4 blob1;
    uniform float4 blob2;
    uniform float4 blob3;

    const float TWO_PI = 6.28318530718;
    const float PI = 3.14159265359;

    float3 baseGradient(float position) {
        if (position < stop1) {
            return mix(base0.rgb, base1.rgb, smoothstep(0.0, stop1, position));
        }
        if (position < stop2) {
            return mix(base1.rgb, base2.rgb, smoothstep(stop1, stop2, position));
        }
        if (position < stop3) {
            return mix(base2.rgb, base3.rgb, smoothstep(stop2, stop3, position));
        }
        return mix(base3.rgb, base4.rgb, smoothstep(stop3, 1.0, position));
    }

    float falloff(float2 point, float2 center, float radius) {
        float distanceFromCenter = distance(point, center) / max(radius, 1.0);
        float edge = clamp(1.0 - distanceFromCenter, 0.0, 1.0);
        return edge * edge * (3.0 - 2.0 * edge);
    }

    float4 composite(float4 under, float4 blob, float coverage) {
        float alpha = clamp(blob.a * intensity * opacity * coverage, 0.0, 0.88);
        float outputAlpha = alpha + under.a * (1.0 - alpha);
        float3 premultiplied = blob.rgb * alpha + under.rgb * under.a * (1.0 - alpha);
        return float4(premultiplied / max(outputAlpha, 0.0001), outputAlpha);
    }

    half4 main(float2 fragCoord) {
        float2 safeResolution = max(resolution, float2(1.0));
        float2 uv = fragCoord / safeResolution;
        float angleRadians = gradientAngle * PI / 180.0;
        float2 gradientDirection = float2(sin(angleRadians), cos(angleRadians));
        float gradientExtent = abs(gradientDirection.x) * safeResolution.x + abs(gradientDirection.y) * safeResolution.y;
        float gradientPosition = clamp(
            dot(fragCoord - safeResolution * 0.5, gradientDirection) / max(gradientExtent, 0.0001) + 0.5,
            0.0,
            1.0
        );
        float radius = max(safeResolution.x, safeResolution.y) * layoutProfile.y;
        float4 color = float4(baseGradient(gradientPosition), opacity);

        float p1 = phasesX.x;
        float2 c1 = float2(
            safeResolution.x * (0.48 + sin(p1) * 0.38 * motionScale * layoutProfile.z),
            safeResolution.y * (0.08 + layoutProfile.x + cos(phasesY.x) * 0.18 * motionScale * layoutProfile.w)
        );
        if (blobCount > 0.5) {
            color = composite(color, blob0, falloff(fragCoord, c1, radius * softness * 0.36));
        }

        float p2 = phasesX.y;
        float2 c2 = float2(
            safeResolution.x * (0.18 + sin(p2 + TWO_PI * 0.275) * 0.30 * motionScale * layoutProfile.z),
            safeResolution.y * (0.24 + layoutProfile.x + cos(p2) * 0.20 * motionScale * layoutProfile.w)
        );
        if (blobCount > 1.5) {
            color = composite(color, blob1, falloff(fragCoord, c2, radius * softness * 0.28));
        }

        float p3 = phasesX.z;
        float2 c3 = float2(
            safeResolution.x * (0.82 - sin(p3 + TWO_PI * 0.45) * 0.34 * motionScale * layoutProfile.z),
            safeResolution.y * (0.12 + layoutProfile.x + cos(phasesY.z) * 0.18 * motionScale * layoutProfile.w)
        );
        if (blobCount > 2.5) {
            color = composite(color, blob2, falloff(fragCoord, c3, radius * softness * 0.30));
        }

        float p4 = phasesX.w;
        float2 c4 = float2(
            safeResolution.x * (0.58 + sin(p4 + TWO_PI * 0.625) * 0.28 * motionScale * layoutProfile.z),
            safeResolution.y * (0.34 + layoutProfile.x + cos(phasesY.w) * 0.16 * motionScale * layoutProfile.w)
        );
        if (blobCount > 3.5) {
            color = composite(color, blob3, falloff(fragCoord, c4, radius * softness * 0.26));
        }
        float edgeDistance = distance(uv, float2(0.5)) / 0.70710678;
        float edge = smoothstep(0.42, 1.0, edgeDistance);
        color.rgb *= 1.0 - clamp(vignette, 0.0, 1.0) * 0.28 * edge;
        // RuntimeShader output must use premultiplied alpha.
        return half4(color.rgb * color.a, color.a);
    }
"""
