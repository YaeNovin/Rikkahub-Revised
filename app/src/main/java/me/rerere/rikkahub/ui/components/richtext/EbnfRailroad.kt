package me.rerere.rikkahub.ui.components.richtext

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class RailroadSpec(
    val type: String,
    val text: String? = null,
    val items: List<RailroadSpec> = emptyList(),
    val normal: Int? = null,
)

/**
 * Converts common EBNF productions to the JSON grammar understood by railroad-diagrams.js.
 * JSON input is left untouched so the existing explicit railroad AST remains supported.
 */
internal fun normalizeRailroadSource(source: String): String {
    val trimmed = source.trim()
    if (runCatching { Json.parseToJsonElement(trimmed) }.isSuccess) return trimmed

    val spec = runCatching { EbnfRailroadParser(trimmed).parse() }
        .getOrElse { error ->
            RailroadSpec(type = "comment", text = "Invalid EBNF: ${error.message.orEmpty()}")
        }
    return Json.encodeToString(spec)
}

internal fun parseEbnfRailroad(source: String): RailroadSpec = EbnfRailroadParser(source).parse()

private class EbnfRailroadParser(source: String) {
    private val tokens = EbnfTokenizer(source).tokens()
    private var index = 0

    fun parse(): RailroadSpec {
        val productions = buildList {
            while (!atEnd()) {
                val production = parseProduction()
                if (production != null) add(production) else advance()
            }
        }
        return when (productions.size) {
            0 -> RailroadSpec(type = "skip")
            1 -> productions.first()
            else -> RailroadSpec(type = "choice", items = productions, normal = 0)
        }
    }

    private fun parseProduction(): RailroadSpec? {
        val name = current().takeIf { it.kind == EbnfTokenKind.IDENTIFIER }
            ?.takeIf { peek().kind == EbnfTokenKind.ASSIGNMENT }
        if (name != null) {
            advance()
            advance()
        }

        val expression = parseChoice(stopSymbols = setOf(";")) ?: return null
        consumeSymbol(";")
        return if (name == null) expression else sequence(
            RailroadSpec(type = "nonterminal", text = name.text),
            RailroadSpec(type = "comment", text = "="),
            expression,
        )
    }

    private fun parseChoice(stopSymbols: Set<String>): RailroadSpec? {
        val alternatives = mutableListOf<RailroadSpec>()
        parseSequence(stopSymbols + "|")?.let(alternatives::add)
        while (consumeSymbol("|")) {
            alternatives += parseSequence(stopSymbols + "|") ?: RailroadSpec(type = "skip")
        }
        return when (alternatives.size) {
            0 -> null
            1 -> alternatives.first()
            else -> RailroadSpec(type = "choice", items = alternatives, normal = 0)
        }
    }

    private fun parseSequence(stopSymbols: Set<String>): RailroadSpec? {
        val items = mutableListOf<RailroadSpec>()
        while (!atEnd() && current().text !in stopSymbols) {
            if (consumeSymbol(",")) continue
            val item = parsePostfix() ?: break
            items += item
        }
        return when (items.size) {
            0 -> null
            1 -> items.first()
            else -> RailroadSpec(type = "sequence", items = items)
        }
    }

    private fun parsePostfix(): RailroadSpec? {
        var item = parsePrimary() ?: return null
        while (true) {
            item = when {
                consumeSymbol("?") -> RailroadSpec(type = "optional", items = listOf(item))
                consumeSymbol("*") -> RailroadSpec(type = "zeroOrMore", items = listOf(item))
                consumeSymbol("+") -> RailroadSpec(type = "oneOrMore", items = listOf(item))
                else -> return item
            }
        }
    }

    private fun parsePrimary(): RailroadSpec? = when (val token = current()) {
        EbnfToken.End -> null
        else -> when (token.kind) {
            EbnfTokenKind.STRING -> {
                advance()
                RailroadSpec(type = "terminal", text = token.text)
            }

            EbnfTokenKind.IDENTIFIER -> {
                advance()
                RailroadSpec(type = "nonterminal", text = token.text)
            }

            EbnfTokenKind.SYMBOL -> when (token.text) {
                "(" -> enclosed(")")
                "[" -> enclosed("]")?.let { RailroadSpec(type = "optional", items = listOf(it)) }
                "{" -> enclosed("}")?.let { RailroadSpec(type = "zeroOrMore", items = listOf(it)) }
                else -> null
            }

            EbnfTokenKind.ASSIGNMENT -> null
        }
    }

    private fun enclosed(closingSymbol: String): RailroadSpec? {
        advance()
        val expression = parseChoice(stopSymbols = setOf(closingSymbol)) ?: RailroadSpec(type = "skip")
        require(consumeSymbol(closingSymbol)) { "Expected '$closingSymbol'" }
        return expression
    }

    private fun sequence(vararg items: RailroadSpec): RailroadSpec = RailroadSpec(
        type = "sequence",
        items = items.toList(),
    )

    private fun current(): EbnfToken = tokens.getOrElse(index) { EbnfToken.End }

    private fun peek(): EbnfToken = tokens.getOrElse(index + 1) { EbnfToken.End }

    private fun atEnd(): Boolean = current() == EbnfToken.End

    private fun advance() {
        if (!atEnd()) index += 1
    }

    private fun consumeSymbol(symbol: String): Boolean {
        if (current().kind != EbnfTokenKind.SYMBOL || current().text != symbol) return false
        advance()
        return true
    }
}

private enum class EbnfTokenKind {
    IDENTIFIER,
    STRING,
    SYMBOL,
    ASSIGNMENT,
}

private data class EbnfToken(
    val kind: EbnfTokenKind,
    val text: String,
) {
    companion object {
        val End = EbnfToken(EbnfTokenKind.SYMBOL, "<eof>")
    }
}

private class EbnfTokenizer(private val source: String) {
    private var index = 0

    fun tokens(): List<EbnfToken> = buildList {
        while (index < source.length) {
            skipIgnored()
            if (index >= source.length) break
            when {
                source.startsWith("::=", index) -> addAndAdvance(EbnfTokenKind.ASSIGNMENT, 3)
                source.startsWith(":=", index) || source.startsWith("->", index) -> addAndAdvance(EbnfTokenKind.ASSIGNMENT, 2)
                source[index] == '=' -> addAndAdvance(EbnfTokenKind.ASSIGNMENT, 1)
                source[index] == '\'' || source[index] == '"' -> add(readString())
                source[index].isLetterOrDigit() || source[index] == '_' || source[index] == '-' -> add(readIdentifier())
                else -> add(EbnfToken(EbnfTokenKind.SYMBOL, source[index++].toString()))
            }
        }
    }

    private fun skipIgnored() {
        while (index < source.length) {
            when {
                source[index].isWhitespace() -> index += 1
                source.startsWith("(*", index) -> {
                    val closing = source.indexOf("*)", index + 2)
                    index = if (closing >= 0) closing + 2 else source.length
                }

                source.startsWith("//", index) -> {
                    val lineEnd = source.indexOf('\n', index + 2)
                    index = if (lineEnd >= 0) lineEnd + 1 else source.length
                }

                source[index] == '#' -> {
                    val lineEnd = source.indexOf('\n', index + 1)
                    index = if (lineEnd >= 0) lineEnd + 1 else source.length
                }

                else -> return
            }
        }
    }

    private fun addAndAdvance(kind: EbnfTokenKind, length: Int): EbnfToken {
        val text = source.substring(index, index + length)
        index += length
        return EbnfToken(kind, text)
    }

    private fun readIdentifier(): EbnfToken {
        val start = index
        while (index < source.length && (source[index].isLetterOrDigit() || source[index] in "_-")) {
            index += 1
        }
        return EbnfToken(EbnfTokenKind.IDENTIFIER, source.substring(start, index))
    }

    private fun readString(): EbnfToken {
        val quote = source[index++]
        val text = buildString {
            while (index < source.length && source[index] != quote) {
                val character = source[index++]
                if (character == '\\' && index < source.length) {
                    append(source[index++])
                } else {
                    append(character)
                }
            }
        }
        require(index < source.length) { "Unterminated string" }
        index += 1
        return EbnfToken(EbnfTokenKind.STRING, text)
    }
}
