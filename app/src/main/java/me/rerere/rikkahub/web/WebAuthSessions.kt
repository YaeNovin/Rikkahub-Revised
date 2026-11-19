package me.rerere.rikkahub.web

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.Payload
import java.util.Date
import java.util.UUID

internal const val WEB_ACCESS_TOKEN_COOKIE = "rikkahub_access"
internal const val WEB_JWT_TTL_MILLIS = 30L * 24 * 60 * 60 * 1000

/** Active sessions belong to one server lifetime. Logout/restart cannot revive old JWTs. */
internal class WebAuthSessions(
    private val now: () -> Long = System::currentTimeMillis,
    private val capacity: Int = 256,
) {
    private val active = linkedMapOf<String, Long>()

    @Synchronized fun issue(secret: String): Pair<String, Long> {
        val issuedAt = now()
        active.entries.removeAll { it.value <= issuedAt }
        while (active.size >= capacity) active.remove(active.keys.first())
        val id = UUID.randomUUID().toString()
        val expiresAt = issuedAt + WEB_JWT_TTL_MILLIS
        val token = JWT.create().withIssuer("rikkahub-web").withAudience("rikkahub-web-client")
            .withSubject("web-access").withJWTId(id).withIssuedAt(Date(issuedAt))
            .withExpiresAt(Date(expiresAt)).sign(Algorithm.HMAC256(secret))
        active[id] = expiresAt
        return token to expiresAt
    }

    fun verifier(secret: String): JWTVerifier = JWT.require(Algorithm.HMAC256(secret))
        .withIssuer("rikkahub-web").withAudience("rikkahub-web-client").withSubject("web-access").build()

    @Synchronized fun isActive(payload: Payload): Boolean =
        payload.subject == "web-access" && (active[payload.id] ?: 0L) > now()

    fun accepts(token: String?, secret: String): Boolean = token != null && secret.isNotBlank() &&
        runCatching { isActive(verifier(secret).verify(token)) }.getOrDefault(false)

    @Synchronized fun revoke(token: String?, secret: String) {
        if (token == null || secret.isBlank()) return
        runCatching { verifier(secret).verify(token).id }.getOrNull()?.let(active::remove)
    }
}

internal fun webSessionCookie(token: String = "", secure: Boolean = false): String =
    "$WEB_ACCESS_TOKEN_COOKIE=$token; Max-Age=${if (token.isEmpty()) 0 else WEB_JWT_TTL_MILLIS / 1000}; Path=/api; HttpOnly; SameSite=Strict" +
        if (secure) "; Secure" else ""
