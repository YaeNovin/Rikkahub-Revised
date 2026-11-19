package me.rerere.rikkahub.data.render

import kotlinx.serialization.json.*
import kotlin.math.abs

internal const val MAX_DECORATIVE_CURVE_VERTICES = 24000

/** Fixed mathematical families, not an expression or code execution interface. */
internal fun decorativeCurveFields(formula: String): Array<String> = when (formula) {
    "hypotrochoid", "epitrochoid" -> arrayOf("formula", "fixed", "rolling", "pen")
    "lissajous" -> arrayOf("formula", "frequencyX", "frequencyY", "phase")
    "harmonic" -> arrayOf("formula", "terms")
    else -> error("curve.formula 仅支持 hypotrochoid、epitrochoid、lissajous、harmonic")
}

private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

internal fun compileDecorativeCurve(layer: JsonObject): JsonObject {
    val formula = layer.text("formula", "hypotrochoid")
    decorativeCurveFields(formula)
    return buildJsonObject {
        put("version", 1)
        put("formula", formula)
        val complexity = when (formula) {
            "hypotrochoid", "epitrochoid" -> {
                val fixed = layer.integer("fixed", 7, 1, 24)
                val rolling = layer.integer("rolling", 3, 1, 12)
                require(formula != "hypotrochoid" || fixed > rolling) { "hypotrochoid.fixed 必须大于 rolling" }
                val divisor = gcd(fixed, rolling)
                val p = fixed / divisor
                val q = rolling / divisor
                put("fixed", p); put("rolling", q)
                // Distance from rolling-circle centre as a multiple of its radius.
                put("pen", layer.num("pen", 0.7, 0.0, 2.0))
                maxOf(q, if (formula == "hypotrochoid") p - q else p + q)
            }
            "lissajous" -> {
                val x = layer.integer("frequencyX", 3, 1, 16)
                val y = layer.integer("frequencyY", 2, 1, 16)
                val divisor = gcd(x, y)
                put("frequencyX", x / divisor); put("frequencyY", y / divisor)
                put("phase", layer.num("phase", 90.0, -360.0, 360.0))
                maxOf(x / divisor, y / divisor)
            }
            else -> {
                val defaults = listOf(
                    buildJsonObject { put("frequency", 6); put("amplitude", 0.2) },
                    buildJsonObject { put("frequency", 12); put("amplitude", 0.08) },
                )
                val terms = if ("terms" in layer) layer.items("terms", 6, required = true) else defaults
                var amplitudeSum = 0.0
                var highestFrequency = 0
                put("terms", JsonArray(terms.map { term ->
                    term.keysOnly("frequency", "amplitude", "phase")
                    val frequency = term.integer("frequency", 6, 1, 16)
                    val amplitude = term.num("amplitude", 0.15, -0.8, 0.8)
                    amplitudeSum += abs(amplitude)
                    highestFrequency = maxOf(highestFrequency, frequency)
                    buildJsonObject {
                        put("frequency", frequency); put("amplitude", amplitude)
                        put("phase", term.num("phase", 0.0, -360.0, 360.0))
                    }
                }))
                require(amplitudeSum <= 0.9 + 1e-9) { "harmonic.terms 振幅绝对值之和不能超过 0.9" }
                // Polar-to-Cartesian multiplication adds one to the highest frequency.
                highestFrequency + 1
            }
        }
        // App-owned density; callers cannot request arbitrarily expensive sampling.
        put("samples", maxOf(256, complexity * 96))
    }
}

/** A second budget for new curves counts their *displayed* symmetry copies.
 * Legacy geometry budgets and old artwork sampling remain unchanged. */
internal fun validateDecorativeCurveBudget(layers: List<JsonObject>) {
    val vertices = layers.sumOf { layer ->
        val curve = layer["curve"] as? JsonObject
        if (curve == null) 0 else (curve.getValue("samples").jsonPrimitive.int + 1) *
            layer.getValue("symmetry").jsonPrimitive.int
    }
    require(vertices <= MAX_DECORATIVE_CURVE_VERTICES) {
        "curve 图层对称复制后的顶点超过 $MAX_DECORATIVE_CURVE_VERTICES，请减少频率、图层或 symmetry"
    }
}
