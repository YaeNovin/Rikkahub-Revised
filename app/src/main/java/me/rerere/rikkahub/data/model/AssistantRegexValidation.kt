package me.rerere.rikkahub.data.model

data class AssistantRegexValidation(
    val errorMessage: String? = null,
) {
    val isValid: Boolean get() = errorMessage == null
}

fun validateAssistantRegex(regex: AssistantRegex): AssistantRegexValidation {
    if (regex.findRegex.isEmpty()) {
        return AssistantRegexValidation("The regular expression cannot be empty")
    }
    val compiled = runCatching { Regex(regex.findRegex) }.getOrElse { error ->
        return AssistantRegexValidation(error.message ?: error::class.java.simpleName)
    }
    validateReplacement(compiled, regex.replaceString)?.let { error ->
        return AssistantRegexValidation(error)
    }
    return AssistantRegexValidation()
}

data class AssistantRegexTestResult(
    val output: String,
    val matchCount: Int,
    val errorMessage: String? = null,
)

/** Applies one rule for the editor preview without allowing malformed data to escape. */
fun testAssistantRegex(regex: AssistantRegex, input: String): AssistantRegexTestResult {
    val compiled = runCatching { Regex(regex.findRegex) }.getOrElse { error ->
        return AssistantRegexTestResult(
            output = input,
            matchCount = 0,
            errorMessage = error.message ?: error::class.java.simpleName,
        )
    }
    validateReplacement(compiled, regex.replaceString)?.let { error ->
        return AssistantRegexTestResult(input, 0, error)
    }
    return runCatching {
        AssistantRegexTestResult(
            output = input.replace(compiled, regex.replaceString),
            matchCount = compiled.findAll(input).count(),
        )
    }.getOrElse { error ->
        AssistantRegexTestResult(
            output = input,
            matchCount = 0,
            errorMessage = error.message ?: error::class.java.simpleName,
        )
    }
}

private fun validateReplacement(regex: Regex, replacement: String): String? {
    val groupCount = regex.toPattern().matcher("").groupCount()
    val declaredNamedGroups = Regex("\\(\\?<([A-Za-z][A-Za-z0-9]*)>")
        .findAll(regex.pattern)
        .map { it.groupValues[1] }
        .toSet()

    // Parse the replacement independently from the user's pattern. Wrapping a
    // pattern breaks expressions with start-sensitive inline flags, while a
    // synthetic pattern cannot validate named groups correctly.
    var index = 0
    while (index < replacement.length) {
        when (replacement[index]) {
            '\\' -> {
                if (index + 1 >= replacement.length) {
                    return "Replacement ends with an unfinished escape"
                }
                index += 2
            }

            '\$' -> {
                if (index + 1 >= replacement.length) {
                    return "Replacement contains an unfinished group reference"
                }
                if (replacement[index + 1] == '{') {
                    val end = replacement.indexOf('}', startIndex = index + 2)
                    if (end < 0) {
                        return "Replacement contains an unfinished named group"
                    }
                    val name = replacement.substring(index + 2, end)
                    if (!name.matches(Regex("[A-Za-z][A-Za-z0-9]*"))) {
                        return "Invalid named group: $name"
                    }
                    if (name !in declaredNamedGroups) {
                        return "Unknown named group: $name"
                    }
                    index = end + 1
                } else if (replacement[index + 1].isDigit()) {
                    var end = index + 1
                    while (end < replacement.length && replacement[end].isDigit()) end++
                    val digits = replacement.substring(index + 1, end)
                    var acceptedLength = digits.length
                    while (acceptedLength > 1) {
                        val candidate = digits.substring(0, acceptedLength).toIntOrNull()
                        if (candidate != null && (candidate == 0 || candidate <= groupCount)) break
                        acceptedLength--
                    }
                    val number = digits.substring(0, acceptedLength).toIntOrNull()
                        ?: return "Invalid numbered group"
                    if (number != 0 && number > groupCount) {
                        return "Unknown numbered group: $number"
                    }
                    // Java's replacement syntax accepts $12 as group 1
                    // followed by a literal 2 when group 12 does not exist.
                    index += 1 + acceptedLength
                } else {
                    return "Replacement contains an invalid group reference"
                }
            }

            else -> index++
        }
    }
    return null
}
