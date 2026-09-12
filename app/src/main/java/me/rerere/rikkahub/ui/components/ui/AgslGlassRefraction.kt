package me.rerere.rikkahub.ui.components.ui

import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect

internal object GlassRefractionRuntime { var failed by mutableStateOf(false) }

internal interface GlassRefractionRenderer {
    fun effect(size: Size, radii: FloatArray, edge: Float, refraction: Float): RenderEffect?
}

internal fun createGlassRefractionRenderer(): GlassRefractionRenderer? {
    if (Build.VERSION.SDK_INT < 33 || GlassRefractionRuntime.failed) return null
    return AgslGlassApi33.create()
}

@RequiresApi(33)
private object AgslGlassApi33 {
    fun create(): GlassRefractionRenderer? = try { Renderer(RuntimeShader(GLASS_REFRACTION_SHADER)) }
    catch (e: RuntimeException) { GlassRefractionRuntime.failed = true; Log.w("LiquidGlass", "AGSL unavailable; using standard glass", e); null }

    private class Renderer(private val shader: RuntimeShader) : GlassRefractionRenderer {
        override fun effect(size: Size, radii: FloatArray, edge: Float, refraction: Float): RenderEffect? = try {
            if (GlassRefractionRuntime.failed || size.width <= 0f || size.height <= 0f) null else {
                shader.setFloatUniform("resolution", size.width, size.height)
                shader.setFloatUniform("radii", radii[0], radii[1], radii[2], radii[3])
                shader.setFloatUniform("edgeWidth", edge.coerceAtLeast(1f))
                shader.setFloatUniform("refraction", refraction.coerceAtLeast(0f))
                // RenderEffect snapshots shader uniforms. Rebuild only when the
                // owning surface's memoized size/parameters change, after setting them.
                android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "backdrop").asComposeRenderEffect()
            }
        } catch (e: RuntimeException) { GlassRefractionRuntime.failed = true; Log.w("LiquidGlass", "Refraction failed; using standard glass", e); null }
    }
}

internal const val GLASS_REFRACTION_SHADER = """
uniform shader backdrop;
uniform float2 resolution;
uniform float4 radii;
uniform float edgeWidth;
uniform float refraction;

half4 main(float2 p) {
    float2 halfSize = resolution * 0.5;
    float2 local = p - halfSize;
    float radius = local.y < 0.0 ? (local.x < 0.0 ? radii.x : radii.y) : (local.x < 0.0 ? radii.w : radii.z);
    radius = clamp(radius, 0.0, min(halfSize.x, halfSize.y));
    float2 q = abs(local) - halfSize + radius;
    float2 outside = max(q, float2(0.0));
    float signedDistance = length(outside) + min(max(q.x, q.y), 0.0) - radius;
    float depth = max(-signedDistance, 0.0);
    float edge = 1.0 - smoothstep(0.0, edgeWidth, depth);
    float2 normal = length(outside) > 0.0001 ? normalize(outside) : (q.x > q.y ? float2(1.0, 0.0) : float2(0.0, 1.0));
    normal *= sign(local);
    float2 samplePoint = clamp(p - normal * refraction * edge * edge, float2(0.5), max(resolution - 0.5, float2(0.5)));
    // Input shader colors are already premultiplied; preserve them unchanged.
    return backdrop.eval(samplePoint);
}
"""
