package me.rerere.search

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
enum class CustomSearchKind { WEB, X, CUSTOM }

@Serializable
data class OptionalSearchValue(val enabled: Boolean = false, val value: String = "")

@Serializable
enum class CustomSearchParameterType { STRING, NUMBER, BOOLEAN, JSON }

@Serializable
data class CustomSearchParameter(
    val id: Uuid = Uuid.random(),
    val enabled: Boolean = false,
    val name: String = "",
    val type: CustomSearchParameterType = CustomSearchParameterType.STRING,
    val value: String = "",
)

/** Preserve a legacy text model parameter when introducing the dedicated field.
 * An explicit disabled field never falls back to an extra parameter. Unsupported
 * or duplicate legacy rows remain visible for correction instead of losing data. */
fun SearchServiceOptions.CustomOptions.withDedicatedSearchModel(): SearchServiceOptions.CustomOptions {
    if (model != null) return this
    val legacy = extraParameters.filter { it.name.trim() == "model" }.singleOrNull()
    return if (legacy != null && legacy.type == CustomSearchParameterType.STRING) {
        copy(model = OptionalSearchValue(legacy.enabled, legacy.value),
            extraParameters = extraParameters.filterNot { it.name.trim() == "model" })
    } else copy(model = OptionalSearchValue())
}
