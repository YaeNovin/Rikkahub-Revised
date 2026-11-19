package me.rerere.rikkahub.data.render

import kotlinx.serialization.json.*

/** A bounded mathematical grammar, not JavaScript/JessieCode. The browser only
 * evaluates this AST with fixed arithmetic functions; no eval or generated code. */
internal class MathExpression(private val source: String, private val variables: Set<String>) {
    private var position = 0
    private var nodes = 0
    private var depth = 0
    companion object {
        val functions = mapOf("sin" to 1, "cos" to 1, "tan" to 1, "asin" to 1, "acos" to 1,
            "atan" to 1, "sqrt" to 1, "abs" to 1, "exp" to 1, "ln" to 1, "log" to 1,
            "log10" to 1, "floor" to 1, "ceil" to 1, "round" to 1, "min" to 2, "max" to 2,
            "pow" to 2, "atan2" to 2)
        val reserved = functions.keys + setOf("x", "y", "t", "theta", "pi", "e", "constructor", "prototype", "__proto__")
        private val number = Regex("(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
    }
    fun parse(): JsonArray {
        require(source.isNotBlank() && source.length <= 512) { "公式需要 1–512 个字符" }
        val result = sum()
        space()
        require(position == source.length) { "公式第 ${position + 1} 位包含不支持的语法；乘法请使用 *" }
        return result
    }
    private fun node(kind: String, vararg values: JsonElement): JsonArray {
        require(++nodes <= 256) { "公式过于复杂" }
        return JsonArray(listOf(JsonPrimitive(kind)) + values)
    }
    private fun space() { while (source.getOrNull(position)?.isWhitespace() == true) position++ }
    private fun take(c: Char): Boolean { space(); return if (source.getOrNull(position) == c) { position++; true } else false }
    private fun sum(): JsonArray {
        var left = product()
        while (true) left = when {
            take('+') -> node("bin", JsonPrimitive("+"), left, product())
            take('-') -> node("bin", JsonPrimitive("-"), left, product())
            else -> return left
        }
    }
    private fun product(): JsonArray {
        var left = unary()
        while (true) left = when {
            take('*') -> node("bin", JsonPrimitive("*"), left, unary())
            take('/') -> node("bin", JsonPrimitive("/"), left, unary())
            else -> return left
        }
    }
    private fun unary(): JsonArray {
        require(++depth <= 32) { "公式嵌套过深" }
        try {
            if (take('+')) return unary()
            if (take('-')) return node("neg", unary())
            val left = atom()
            return if (take('^')) node("bin", JsonPrimitive("^"), left, unary()) else left
        } finally { depth-- }
    }
    private fun atom(): JsonArray {
        if (take('(')) {
            val value = sum()
            require(take(')')) { "公式缺少右括号" }
            return value
        }
        space()
        val numeric = number.find(source, position)?.takeIf { it.range.first == position }
        if (numeric != null) {
            position = numeric.range.last + 1
            val value = numeric.value.toDoubleOrNull()
            require(value != null && value.isFinite() && kotlin.math.abs(value) <= 1e12) { "公式常量超出范围" }
            return node("num", JsonPrimitive(value))
        }
        val start = position
        while (source.getOrNull(position)?.let { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' } == true) position++
        require(position > start) { "公式第 ${position + 1} 位无效" }
        val name = source.substring(start, position)
        if (take('(')) {
            val arity = functions[name] ?: error("不支持的数学函数：$name")
            val arguments = mutableListOf<JsonElement>(sum())
            while (take(',')) { require(arguments.size < arity) { "函数参数数量不符" }; arguments.add(sum()) }
            require(take(')') && arguments.size == arity) { "函数参数数量不符或缺少右括号" }
            return node("call", JsonPrimitive(name), JsonArray(arguments))
        }
        if (name.lowercase() == "pi") return node("num", JsonPrimitive(kotlin.math.PI))
        if (name == "e") return node("num", JsonPrimitive(kotlin.math.E))
        require(name in variables) { "未定义的变量：$name" }
        return node("var", JsonPrimitive(name))
    }
}
