package me.rerere.rikkahub.data.render

import kotlinx.serialization.json.*

/** Shared, bounded entertainment effects. No emitter scripts or native/GPU settings. */
internal fun compileDecorativeEffects(input: JsonObject): JsonObject {
    val effects = if ("effects" in input) input["effects"] as? JsonObject ?: error("effects 需要对象") else buildJsonObject {}
    effects.keysOnly("stardust", "trail", "reveal")
    fun obj(name: String): JsonObject? = if (name in effects) effects[name] as? JsonObject ?: error("effects.$name 需要对象") else null
    val dust = obj("stardust")?.let { p ->
        p.keysOnly("count", "color", "size", "radius", "speed", "seed")
        buildJsonObject {
            put("count", p.integer("count", 48, 1, 128)); put("color", p.color("color", "#a78bfa"))
            put("size", p.num("size", 1.0, 0.3, 2.5)); put("radius", p.num("radius", 95.0, 5.0, 100.0))
            put("speed", p.num("speed", 12.0, -60.0, 60.0)); put("seed", p.integer("seed", 42, 0, Int.MAX_VALUE))
        }
    }
    val trail = obj("trail")?.let { p ->
        require(dust != null) { "trail 需要 stardust，拖尾仅跟随星尘" }
        p.keysOnly("samples", "duration", "opacity")
        buildJsonObject {
            put("samples", p.integer("samples", 5, 1, 8))
            put("duration", p.num("duration", 0.6, 0.1, 1.5)); put("opacity", p.num("opacity", 0.35, 0.05, 0.6))
        }
    }
    val reveal = obj("reveal")?.let { p ->
        p.keysOnly("duration", "stagger")
        buildJsonObject { put("duration", p.num("duration", 0.8, 0.2, 3.0)); put("stagger", p.num("stagger", 0.15, 0.0, 0.5)) }
    }
    return buildJsonObject {
        dust?.let { put("stardust", it) }; trail?.let { put("trail", it) }; reveal?.let { put("reveal", it) }
    }
}
