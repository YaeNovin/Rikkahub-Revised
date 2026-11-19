package me.rerere.rikkahub.data.render

import kotlinx.serialization.json.*
import kotlin.math.pow

internal const val MAX_PATTERN_VERTICES = 24000

/** Decorative presets only: no user programs, recursive rules, shaders or URLs. */
internal fun compilePattern(input: JsonObject): JsonObject {
    input.keysOnly("title", "name", "description", "background", "backgroundColor", "color", "lineColor", "palette", "colors", "seed", "animate", "glow", "glowColor", "glowSpread", "glowPulse", "animation", "layers", "effects")
    val defaultColor = input.color("lineColor", input.color("color", "#a78bfa"))
    val defaultPalette = if ("lineColor" in input || "color" in input) listOf(defaultColor) else listOf("#a78bfa", "#22d3ee", "#f472b6", "#fbbf24")
    val palette = input.palette("palette", input.palette("colors", defaultPalette, 12), 12)
    var vertices = 0
    val layers = input.items("layers", 12, required = true).mapIndexed { index, layer ->
        val type = layer.text("type")
        val color = palette[index % palette.size]
        if (type in setOf("ring", "circle", "star", "hexagram", "polygon", "ticks", "rays", "arc", "dot", "orbit", "curve")) {
            val (data, count) = compileRadialLayer(layer, color)
            vertices += (when (type) {
                "ring", "circle" -> 129
                "curve" -> data.getValue("curve").jsonObject.getValue("samples").jsonPrimitive.int + 1
                else -> count * 2
            }) +
                if (data["background"]?.jsonPrimitive?.content?.let { it != "none" } == true) 129 else 0
            data
        } else {
            val common = arrayOf("type", "radius", "color", "lineColor", "colors", "width", "opacity", "rotation", "speed", "label", "name", "fillOpacity", "lineCap", "lineJoin", "dash", "dashOffset", "animation", "symmetry", "mirror", "glow")
            when (type) {
                "mandala" -> layer.keysOnly(*common, "petals", "rings", "innerRadius", "petalWidth")
                "rose" -> layer.keysOnly(*common, "numerator", "denominator", "phase")
                "fractal" -> layer.keysOnly(*common, "variant", "depth", "angle", "shrink")
                "path", "freeform" -> layer.keysOnly(*common, "points", "closed", "fill")
                else -> error("纹样图层仅支持 mandala、rose、fractal、ring、star、polygon、ticks、rays、path、curve")
            }
            val radius = layer.num("radius", 80.0, 1.0, 96.0)
            buildJsonObject {
                put("type", if (type == "freeform") "path" else type); put("radius", radius); put("color", layer.color("color", layer.color("lineColor", color)))
                put("palette", JsonArray(layer.palette("colors", if ("color" in layer || "lineColor" in layer) listOf(layer.color("color", layer.color("lineColor", color))) else palette, 12).map(::JsonPrimitive)))
                put("label", layer.text("label", layer.text("name", "", 64), 64))
                put("width", layer.num("width", 0.7, 0.25, 5.0)); put("opacity", layer.num("opacity", 1.0, 0.05, 1.0))
                put("rotation", layer.num("rotation", 0.0, -360.0, 360.0)); put("speed", layer.num("speed", 0.0, -60.0, 60.0))
                put("lineCap", layer.text("lineCap", "round", 12)); put("lineJoin", layer.text("lineJoin", "round", 12))
                require(layer.text("lineCap", "round", 12) in setOf("round", "butt", "square"))
                require(layer.text("lineJoin", "round", 12) in setOf("round", "bevel", "miter"))
                put("fillOpacity", layer.num("fillOpacity", 0.22, 0.0, 1.0)); put("symmetry", layer.integer("symmetry", 1, 1, 12)); put("mirror", layer.flag("mirror", false)); put("animation", layer.animation())
                when (type) {
                    "mandala" -> {
                        val petals = layer.integer("petals", 12, 3, 32)
                        val rings = layer.integer("rings", 4, 1, 6)
                        put("petals", petals); put("rings", rings)
                        put("innerRadius", layer.num("innerRadius", radius * 0.12, 0.0, radius * 0.8))
                        put("petalWidth", layer.num("petalWidth", 0.85, 0.1, 1.5))
                        vertices += rings * (petals * 49 + 129)
                    }
                    "rose" -> {
                        val n = layer.integer("numerator", 5, 1, 24)
                        val d = layer.integer("denominator", 1, 1, 12)
                        fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
                        val factor = gcd(n, d)
                        put("numerator", n / factor); put("denominator", d / factor)
                        put("phase", layer.num("phase", 0.0, -360.0, 360.0))
                        vertices += maxOf(256, (n / factor) * 192 + (d / factor) * 64) + 1
                    }
                    "fractal" -> {
                        val variant = layer.text("variant", "koch")
                        require(variant in setOf("koch", "sierpinski", "tree")) { "fractal.variant 仅支持 koch、sierpinski、tree" }
                        require(variant == "tree" || "angle" !in layer && "shrink" !in layer) { "angle/shrink 仅适用于 tree" }
                        val depth = layer.integer("depth", 4, 0, if (variant == "koch") 5 else 7)
                        put("variant", variant); put("depth", depth)
                        if (variant == "tree") {
                            put("angle", layer.num("angle", 28.0, 10.0, 65.0))
                            put("shrink", layer.num("shrink", 0.7, 0.45, 0.78))
                        }
                        vertices += when (variant) {
                            "koch" -> 3 * 4.0.pow(depth).toInt() + 1
                            "sierpinski" -> 3 * 3.0.pow(depth).toInt()
                            else -> 2 * (2.0.pow(depth + 1).toInt() - 1)
                        }
                    }
                    "path", "freeform" -> { put("points", layer.points()); put("closed", layer.flag("closed", false)); put("fill", layer.color("fill", "none", true)); vertices += (layer["points"] as JsonArray).size }
                }
            }
        }
    }
    require(vertices <= MAX_PATTERN_VERTICES) { "纹样总顶点预算超过 $MAX_PATTERN_VERTICES，请减少图层、花瓣或分形层级" }
    validateDecorativeCurveBudget(layers)
    return buildJsonObject {
        put("kind", "pattern"); put("title", input.text("title", input.text("name", "程序化纹样"))); put("description", input.text("description", "", 160))
        put("background", input.color("background", input.color("backgroundColor", "#101426")))
        put("palette", JsonArray(palette.map(::JsonPrimitive)))
        put("seed", input.integer("seed", 42, 0, Int.MAX_VALUE))
        val glow = input.glowObject()
        put("animate", input.flag("animate", false)); put("glow", input.glowAmount()); put("glowColor", input.color("glowColor", glow?.color("color", palette.first()) ?: palette.first()))
        put("glowSpread", input.num("glowSpread", glow?.num("spread", 1.0, 0.25, 4.0) ?: 1.0, 0.25, 4.0)); put("glowPulse", input.flag("glowPulse", glow?.flag("pulse", false) ?: false)); put("animation", input.animation())
        put("layers", JsonArray(layers)); put("vertexBudget", MAX_PATTERN_VERTICES); put("effects", compileDecorativeEffects(input))
    }
}
