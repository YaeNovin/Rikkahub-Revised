package me.rerere.rikkahub.web

import io.ktor.server.sse.ServerSSESession
import io.ktor.util.AttributeKey
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal val WebSessionValidity = AttributeKey<() -> Boolean>("WebSessionValidity")

/** Authentication at stream creation is insufficient after logout, expiry or password rotation. */
internal fun ServerSSESession.watchWebSession(anonymousAllowed: () -> Boolean) {
    val valid = call.attributes.getOrNull(WebSessionValidity) ?: anonymousAllowed
    launch {
        while (isActive) {
            if (!valid()) {
                this@watchWebSession.cancel("Web session expired or revoked")
                return@launch
            }
            delay(1000)
        }
    }
}
