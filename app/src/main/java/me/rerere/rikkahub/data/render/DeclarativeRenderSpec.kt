package me.rerere.rikkahub.data.render

import kotlinx.serialization.json.*

internal const val MAX_DECLARATIVE_SOURCE_CHARS = 32768
internal fun isDeclarativeRenderer(id: String) = id in setOf("math_plot", "sigil", "pattern")

/** One validation boundary shared by tools and Markdown fences. No arbitrary
 * JSXGraph options, functions, URLs, SVG paths or HTML are accepted. */
internal fun compileDeclarativeRenderSpec(id: String, source: String): JsonObject {
    require(source.length <= MAX_DECLARATIVE_SOURCE_CHARS) { "绘图数据超过 32768 字符" }
    var depth = 0; var quoted = false; var escaped = false
    source.forEach { c ->
        if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
        else when (c) { '"' -> quoted = true; '{', '[' -> { require(++depth <= 32) { "绘图数据嵌套过深" } }; '}', ']' -> depth-- }
    }
    val input = Json.parseToJsonElement(source) as? JsonObject ?: error("绘图参数必须是 JSON 对象")
    return when (id) { "math_plot" -> compileMath(input); "sigil" -> compileSigil(input); "pattern" -> compilePattern(input); else -> error("未知绘图类型") }
}

internal fun JsonObject.keysOnly(vararg allowed: String) { require(keys.all { it in allowed }) { "不支持的字段：${(keys - allowed.toSet()).first()}" } }
internal fun JsonObject.text(key: String, default: String = "", limit: Int = 96): String {
    if (key !in this) return default
    val value = this[key] as? JsonPrimitive
    require(value?.isString == true && value.content.length <= limit) { "$key 必须是长度不超过 $limit 的字符串" }
    return value.content
}
internal fun JsonObject.num(key: String, default: Double, min: Double, max: Double): Double {
    if (key !in this) return default
    val value = this[key] as? JsonPrimitive
    val number = value?.takeUnless { it.isString }?.doubleOrNull
    require(number != null && number.isFinite() && number in min..max) { "$key 需要 $min 到 $max 的有限数字" }
    return number
}
internal fun JsonObject.integer(key: String, default: Int, min: Int, max: Int): Int {
    val value = num(key, default.toDouble(), min.toDouble(), max.toDouble())
    require(value % 1.0 == 0.0) { "$key 需要整数" }; return value.toInt()
}
internal fun JsonObject.flag(key: String, default: Boolean): Boolean {
    if (key !in this) return default
    val value = this[key] as? JsonPrimitive
    return value?.takeUnless { it.isString }?.booleanOrNull ?: error("$key 需要布尔值")
}
internal fun JsonObject.items(key: String, max: Int, required: Boolean = false): List<JsonObject> {
    if (key !in this && !required) return emptyList()
    val items = this[key] as? JsonArray ?: error("$key 需要数组")
    require(items.size <= max && (!required || items.isNotEmpty())) { "$key 数量需要 ${if (required) 1 else 0}–$max" }
    return items.map { it as? JsonObject ?: error("$key 的条目必须是对象") }
}
private fun JsonObject.pair(key: String, default: List<Double>? = null, ordered: Boolean = false): JsonArray {
    val array = this[key] as? JsonArray ?: if (key !in this && default != null) JsonArray(default.map(::JsonPrimitive)) else error("$key 需要两个数字的数组")
    require(array.size == 2) { "$key 需要两个数字" }
    val values = array.map { (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.doubleOrNull }
    require(values.all { it != null && it.isFinite() && kotlin.math.abs(it) <= 1e6 }) { "$key 坐标超出范围" }
    if (ordered) require(values[1]!! - values[0]!! >= 1e-6) { "$key 下限必须小于上限" }
    return JsonArray(values.map { JsonPrimitive(requireNotNull(it)) })
}
internal fun JsonObject.color(key: String, default: String, allowNone: Boolean = false): String {
    val value = text(key, default, 16)
    require(Regex("#[0-9a-fA-F]{6}|#[0-9a-fA-F]{3}").matches(value) || allowNone && value == "none") { "$key 仅接受十六进制颜色${if (allowNone) "或 none" else ""}" }
    return value
}
internal fun JsonObject.palette(key: String, default: List<String>, max: Int = 12): List<String> {
    if (key !in this) return default
    val values = this[key] as? JsonArray ?: error("$key 需要颜色数组")
    require(values.size in 1..max) { "$key 需要 1–$max 个十六进制颜色" }
    return values.map { value ->
        buildJsonObject { put("color", value) }.color("color", default.first())
    }
}

/** Shared bounded animation choreography. It describes motion; it never carries code. */
internal fun JsonObject.animation(key: String = "animation"): JsonObject {
    if (key !in this) return buildJsonObject { }
    val value = this[key] as? JsonObject ?: error("$key 需要对象")
    value.keysOnly("mode", "duration", "delay", "stagger", "easing", "loop", "amplitude", "frequency", "phase")
    val mode = value.text("mode", "rotate", 16).lowercase()
    require(mode in setOf("rotate", "pulse", "draw", "sequence", "flicker", "orbit")) { "animation.mode 不受支持" }
    val easing = value.text("easing", "smooth", 16).lowercase()
    require(easing in setOf("linear", "smooth", "ease_in", "ease_out", "elastic")) { "animation.easing 不受支持" }
    return buildJsonObject {
        put("mode", mode)
        put("duration", value.num("duration", 1.8, 0.2, 30.0))
        put("delay", value.num("delay", 0.0, 0.0, 10.0))
        put("stagger", value.num("stagger", 0.0, 0.0, 2.0))
        put("easing", easing)
        put("loop", value.flag("loop", true))
        put("amplitude", value.num("amplitude", 0.12, 0.0, 1.0))
        put("frequency", value.num("frequency", 1.0, 0.1, 8.0))
        put("phase", value.num("phase", 0.0, -360.0, 360.0))
    }
}

internal fun JsonObject.points(key: String = "points", max: Int = 256): JsonArray {
    val values = this[key] as? JsonArray ?: error("$key 需要点数组")
    require(values.size in 2..max) { "$key 需要 2–$max 个点" }
    return JsonArray(values.map { point ->
        val pair = point as? JsonArray ?: error("$key 的点必须是 [x,y]")
        require(pair.size == 2) { "$key 的点必须是 [x,y]" }
        val coordinates = pair.map { (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.doubleOrNull }
        require(coordinates.all { it != null && it.isFinite() && kotlin.math.abs(it) <= 100.0 }) { "$key 坐标需要在 -100 到 100 之间" }
        JsonArray(coordinates.map { JsonPrimitive(requireNotNull(it)) })
    })
}

internal fun JsonObject.glowAmount(default: Double = 0.0): Double {
    val value = this["glow"] ?: return default
    return when (value) {
        is JsonPrimitive -> value.takeUnless { it.isString }?.doubleOrNull?.also { require(it.isFinite() && it in 0.0..3.0) }
            ?: error("glow 需要 0 到 3 的有限数字或对象")
        is JsonObject -> value.num("amount", default, 0.0, 3.0)
        else -> error("glow 需要数字或对象")
    }
}
internal fun JsonObject.glowObject(): JsonObject? = (this["glow"] as? JsonObject)?.also {
    it.keysOnly("amount", "color", "spread", "pulse")
}
private val colors = listOf("#7c3aed", "#0284c7", "#dc2626", "#059669", "#d97706", "#db2777", "#6366f1", "#0d9488")

private fun compileMath(input: JsonObject): JsonObject {
    input.keysOnly("title", "xRange", "yRange", "equalAspect", "grid", "curves", "parameters", "primitives")
    val names = mutableSetOf<String>()
    val parameters = input.items("parameters", 4).map { p ->
        p.keysOnly("name", "label", "min", "max", "value", "step")
        val name = p.text("name", limit = 16)
        require(Regex("[a-z][a-z0-9_]{0,15}").matches(name) && name !in MathExpression.reserved && names.add(name)) { "参数名无效、重复或与保留名称冲突" }
        val label = p.text("label", name, 64)
        val min = p.num("min", -5.0, -1e6, 1e6); val max = p.num("max", 5.0, -1e6, 1e6)
        require(max - min >= 1e-6) { "参数上下限无效" }
        val value = p.num("value", min, min, max)
        // Omitted step means a continuous slider. HTML range inputs otherwise
        // silently round an initial value (e.g. 1 -> .99 for a 0..3 / .03 grid).
        val step = if ("step" in p) {
            val number = p.num("step", 1.0, (max - min) / 10000, max - min)
            val ticks = (value - min) / number
            if (kotlin.math.abs(ticks - kotlin.math.round(ticks)) <= 1e-7) JsonPrimitive(number)
            else JsonPrimitive("any")
        } else JsonPrimitive("any")
        // Off-grid initial values use continuous movement: browsers otherwise
        // round the initial value silently, changing the plotted mathematics.
        buildJsonObject {
            put("name", name); put("label", label); put("min", min); put("max", max)
            put("value", value); put("step", step)
            put("continuousFallback", "step" in p && step.content == "any")
        }
    }
    val curves = input.items("curves", 8).mapIndexed { index, c ->
        val type = c.text("type", "function")
        when (type) {
            "function" -> c.keysOnly("type", "expression", "range", "label", "color")
            "parametric" -> c.keysOnly("type", "x", "y", "range", "label", "color")
            "polar" -> c.keysOnly("type", "r", "range", "label", "color")
            else -> error("曲线类型仅支持 function、parametric、polar")
        }
        buildJsonObject {
            put("type", type); put("label", c.text("label", "曲线 ${index + 1}", 64)); put("color", c.color("color", colors[index]))
            if ("range" in c || type != "function") put("range", c.pair("range", listOf(0.0, 2 * kotlin.math.PI), ordered = true))
            when (type) {
                "function" -> put("expression", MathExpression(c.text("expression", limit = 512), names + "x").parse())
                "parametric" -> { put("x", MathExpression(c.text("x", limit = 512), names + "t").parse()); put("y", MathExpression(c.text("y", limit = 512), names + "t").parse()) }
                "polar" -> put("r", MathExpression(c.text("r", limit = 512), names + "theta").parse())
            }
        }
    }
    val primitives = input.items("primitives", 32).map { p ->
        val type = p.text("type")
        when (type) {
            "point" -> p.keysOnly("type", "at", "label", "color")
            "segment", "vector" -> p.keysOnly("type", "from", "to", "label", "color")
            "circle" -> p.keysOnly("type", "center", "radius", "label", "color")
            else -> error("图元类型仅支持 point、segment、vector、circle")
        }
        buildJsonObject {
            put("type", type); put("color", p.color("color", "#0284c7")); put("label", p.text("label", limit = 32))
            when (type) {
                "point" -> { put("at", p.pair("at")); put("label", p.text("label", limit = 32)) }
                "segment", "vector" -> { put("from", p.pair("from")); put("to", p.pair("to")) }
                "circle" -> { put("center", p.pair("center")); put("radius", p.num("radius", 1.0, 1e-6, 1e6)) }
            }
        }
    }
    require(curves.isNotEmpty() || primitives.isNotEmpty()) { "至少需要一条曲线或一个图元" }
    return buildJsonObject {
        put("kind", "math_plot"); put("title", input.text("title", "数理绘图"))
        put("xRange", input.pair("xRange", listOf(-8.0, 8.0), true)); put("yRange", input.pair("yRange", listOf(-5.0, 5.0), true))
        put("equalAspect", input.flag("equalAspect", true)); put("grid", input.flag("grid", true))
        put("parameters", JsonArray(parameters)); put("curves", JsonArray(curves)); put("primitives", JsonArray(primitives))
    }
}

private fun compileSigil(input: JsonObject): JsonObject {
    input.keysOnly("title", "name", "description", "style", "theme", "background", "backgroundColor", "color", "lineColor", "palette", "colors", "glow", "glowColor", "glowSpread", "glowPulse", "animate", "animation", "layers", "effects")
    val color = input.color("color", input.color("lineColor", input.palette("colors", listOf("#7c3aed"), 12).first()))
    val palette = input.palette("palette", input.palette("colors", listOf(color), 12), 12)
    var elements = 0
    val layers = input.items("layers", 24, required = true).mapIndexed { index, layer ->
        val (data, count) = compileRadialLayer(layer, palette[index % palette.size])
        elements += count
        data
    }
    require(elements <= 1500) { "图层元素总数超过 1500，请简化图案" }
    validateDecorativeCurveBudget(layers)
    return buildJsonObject {
        put("kind", "sigil"); put("title", input.text("title", input.text("name", "几何法阵"))); put("description", input.text("description", "", 160))
        put("background", input.color("background", input.color("backgroundColor", "#101426"))); put("palette", JsonArray(palette.map(::JsonPrimitive)))
        val glow = input.glowObject()
        put("glow", input.glowAmount()); put("glowColor", input.color("glowColor", glow?.color("color", palette.first()) ?: palette.first()))
        put("glowSpread", input.num("glowSpread", glow?.num("spread", 1.0, 0.25, 4.0) ?: 1.0, 0.25, 4.0)); put("glowPulse", input.flag("glowPulse", glow?.flag("pulse", false) ?: false))
        put("animate", input.flag("animate", false)); put("animation", input.animation()); put("layers", JsonArray(layers)); put("effects", compileDecorativeEffects(input))
    }
}

/** Shared radial geometry contract for SVG sigils and p5 pattern layers. */
internal fun compileRadialLayer(layer: JsonObject, color: String): Pair<JsonObject, Int> {
    var elements = 0
    val type = layer.text("type")
    val common = arrayOf("type", "radius", "background", "color", "lineColor", "colors", "width", "opacity", "rotation", "speed", "label", "name", "fillOpacity", "lineCap", "lineJoin", "dash", "dashOffset", "animation", "symmetry", "mirror", "glow")
    when (type) {
        "ring", "circle" -> layer.keysOnly(*common)
        "star", "polygon", "hexagram" -> layer.keysOnly(*common, "points", "step", "fill")
        "ticks" -> layer.keysOnly(*common, "count", "length")
        "rays" -> layer.keysOnly(*common, "count", "innerRadius")
        "text_ring" -> layer.keysOnly(*common, "text", "fontSize")
        "arc" -> layer.keysOnly(*common, "startAngle", "endAngle")
        "dot" -> layer.keysOnly(*common, "size", "fill")
        "orbit" -> layer.keysOnly(*common, "count", "dotSize", "innerRadius")
        "path", "freeform" -> layer.keysOnly(*common, "points", "closed", "fill")
        "curve" -> layer.keysOnly(*common, *decorativeCurveFields(layer.text("formula", "hypotrochoid")), "fill")
        else -> error("图层仅支持 ring、circle、star、hexagram、polygon、ticks、rays、arc、dot、orbit、text_ring、path、curve")
    }
    val radius = layer.num("radius", 70.0, 1.0, 96.0)
    val result = buildJsonObject {
        val canonicalType = when (type) { "circle" -> "ring"; "hexagram" -> "star"; "freeform" -> "path"; else -> type }
        put("type", canonicalType); put("radius", radius); put("color", layer.color("color", layer.color("lineColor", color)))
        if ("background" in layer) put("background", layer.color("background", "none", allowNone = true))
        put("palette", JsonArray(layer.palette("colors", listOf(layer.color("color", layer.color("lineColor", color))), 12).map(::JsonPrimitive)))
        put("label", layer.text("label", layer.text("name", "", 64), 64))
        put("width", layer.num("width", 1.0, 0.25, 5.0)); put("opacity", layer.num("opacity", 1.0, 0.05, 1.0))
        put("rotation", layer.num("rotation", 0.0, -360.0, 360.0)); put("speed", layer.num("speed", 0.0, -60.0, 60.0))
        put("fillOpacity", layer.num("fillOpacity", 0.22, 0.0, 1.0)); put("lineCap", layer.text("lineCap", "round", 12)); put("lineJoin", layer.text("lineJoin", "round", 12))
        require(layer.text("lineCap", "round", 12) in setOf("round", "butt", "square"))
        require(layer.text("lineJoin", "round", 12) in setOf("round", "bevel", "miter"))
        put("symmetry", layer.integer("symmetry", 1, 1, 12)); put("mirror", layer.flag("mirror", false)); put("animation", layer.animation())
        when (type) {
            "star", "polygon", "hexagram" -> {
                val points = if (type == "hexagram") 6 else layer.integer("points", 6, 3, 32)
                val step = if (type == "polygon") 1 else if (type == "hexagram") 2 else layer.integer("step", if (points > 4) 2 else 1, 1, (points - 1) / 2)
                require(type != "polygon" || "step" !in layer || layer.integer("step", 1, 1, 1) == 1) { "polygon 的 step 必须为 1" }
                put("points", points); put("step", step); put("fill", layer.color("fill", "none", true)); elements += points
            }
            "ticks", "rays" -> {
                val count = layer.integer("count", 36, 3, 128); put("count", count); elements += count
                if (type == "ticks") put("length", layer.num("length", minOf(4.0, radius), 0.5, radius))
                else put("innerRadius", layer.num("innerRadius", radius * 0.7, 0.0, radius))
            }
            "arc" -> { put("startAngle", layer.num("startAngle", 0.0, -360.0, 360.0)); put("endAngle", layer.num("endAngle", 90.0, -360.0, 360.0)); require(layer.num("endAngle", 90.0, -360.0, 360.0) > layer.num("startAngle", 0.0, -360.0, 360.0)); elements += 33 }
            "dot" -> { put("size", layer.num("size", 2.0, 0.5, 8.0)); put("fill", layer.color("fill", layer.color("color", color))); elements++ }
            "orbit" -> { put("count", layer.integer("count", 8, 1, 32)); put("dotSize", layer.num("dotSize", 1.5, 0.5, 6.0)); put("innerRadius", layer.num("innerRadius", radius * .7, 0.0, radius)); elements += layer.integer("count", 8, 1, 32) + 33 }
            "text_ring" -> { val text = layer.text("text"); require(text.isNotBlank()) { "环形文字不能为空" }; put("text", text); put("fontSize", layer.num("fontSize", 5.0, 2.0, 10.0)); elements += text.length }
            "path" -> { put("points", layer.points()); put("closed", layer.flag("closed", false)); put("fill", layer.color("fill", "none", true)); elements += (layer["points"] as JsonArray).size }
            "curve" -> { put("curve", compileDecorativeCurve(layer)); put("fill", layer.color("fill", "none", true)); elements++ }
            else -> elements++
        }
    }
    if (layer["background"] != null && layer.color("background", "none", allowNone = true) != "none") elements++
    return result to elements
}
