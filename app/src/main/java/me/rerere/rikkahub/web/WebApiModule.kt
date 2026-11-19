package me.rerere.rikkahub.web

import android.content.Context
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.web.dto.ErrorResponse
import me.rerere.rikkahub.web.dto.WebAuthTokenRequest
import me.rerere.rikkahub.web.dto.WebAuthTokenResponse
import me.rerere.rikkahub.web.routes.aiIconRoutes
import me.rerere.rikkahub.web.routes.assetsRoutes
import me.rerere.rikkahub.web.routes.conversationRoutes
import me.rerere.rikkahub.web.routes.eventsRoutes
import me.rerere.rikkahub.web.routes.filesRoutes
import me.rerere.rikkahub.web.routes.folderRoutes
import me.rerere.rikkahub.web.routes.settingsRoutes
import me.rerere.rikkahub.web.routes.configurationRoutes
import me.rerere.rikkahub.web.routes.imageRoutes
import java.security.MessageDigest
import java.util.UUID

private const val WEB_AUTH_REALM = "rikkahub-web-api"

/**
 * Configure Web API for the Ktor application.
 * This should be called from app module when starting the web server.
 *
 * Example usage:
 * ```
 * startWebServer(port = 8080) {
 *     configureWebApi(context, chatService, conversationRepo, settingsStore, filesManager)
 * }
 * ```
 */
fun Application.configureWebApi(
    context: Context,
    chatService: ChatService,
    conversationRepo: ConversationRepository,
    folderRepo: FolderRepository,
    settingsStore: SettingsStore,
    filesManager: FilesManager
) {
    val sessions = WebAuthSessions()
    val webSettings = org.koin.core.context.GlobalContext.get().get<WebSettings>().store

    install(ContentNegotiation) {
        json(JsonInstant)
    }

    install(StatusPages) {
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(status, ErrorResponse("Not Found", status.value))
        }
        exception<ApiException> { call, cause ->
            call.respond(cause.status, ErrorResponse(cause.message, cause.status.value))
        }
        exception<Throwable> { call, cause ->
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(cause.message ?: "Internal server error", 500)
            )
        }
    }

    run {
        install(Authentication) {
            jwt("auth-jwt") {
                skipWhen { !settingsStore.settingsFlow.value.webServerJwtEnabled }
                realm = WEB_AUTH_REALM
                verifier { _ ->
                    // The signing secret is random and independent from the user password.
                    val secret = settingsStore.settingsFlow.value.webServerJwtSecret.ifBlank {
                        // Keep protected routes closed when jwt is enabled but password is missing.
                        "__missing_secret_${UUID.randomUUID()}__"
                    }
                    sessions.verifier(secret)
                }
                authHeader { call ->
                    extractAccessToken(
                        authorizationHeader = call.request.headers[HttpHeaders.Authorization],
                        cookieToken = call.request.cookies[WEB_ACCESS_TOKEN_COOKIE]
                    )?.let { token ->
                        HttpAuthHeader.Single("Bearer", token)
                    }
                }
                validate { credential ->
                    val currentPassword = settingsStore.settingsFlow.value.webServerAccessPassword
                    if (currentPassword.isBlank() || !sessions.isActive(credential.payload)) {
                        null
                    } else {
                        val token = extractAccessToken(
                            request.headers[HttpHeaders.Authorization], request.cookies[WEB_ACCESS_TOKEN_COOKIE]
                        )
                        attributes.put(WebSessionValidity) {
                            val current = settingsStore.settingsFlow.value
                            !current.webServerJwtEnabled || (current.webServerAccessPassword.isNotBlank() && sessions.accepts(token, current.webServerJwtSecret))
                        }
                        io.ktor.server.auth.jwt.JWTPrincipal(credential.payload)
                    }
                }
                challenge { _, _ ->
                    val currentPassword = settingsStore.settingsFlow.value.webServerAccessPassword
                    if (currentPassword.isBlank()) {
                        call.respond(
                            HttpStatusCode.Forbidden,
                            ErrorResponse("Access password is not configured", HttpStatusCode.Forbidden.value)
                        )
                    } else {
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            ErrorResponse("Unauthorized", HttpStatusCode.Unauthorized.value)
                        )
                    }
                }
            }
        }
    }

    routing {
        route("/api") {
            post("/auth/token") {
                val settings = settingsStore.settingsFlow.value
                if (!settings.webServerJwtEnabled) {
                    throw BadRequestException("JWT auth is disabled")
                }

                val accessPassword = settings.webServerAccessPassword
                if (accessPassword.isBlank()) {
                    throw BadRequestException("Access password is not configured")
                }

                val request = call.receive<WebAuthTokenRequest>()
                if (!secureEquals(request.password, accessPassword)) {
                    throw UnauthorizedException("Invalid password")
                }

                val secret = settingsStore.ensureWebServerJwtSecret()
                val (token, expiresAt) = sessions.issue(secret)
                // Media elements cannot attach Authorization headers. Keep the token in a
                // restricted, HttpOnly same-site cookie instead of putting it in a URL.
                call.response.headers.append(
                    HttpHeaders.SetCookie,
                    webSessionCookie(token, secure = call.request.local.scheme == "https")
                )
                call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                call.respond(
                    HttpStatusCode.OK,
                    WebAuthTokenResponse(
                        token = token,
                        expiresAt = expiresAt
                    )
                )
            }

            // Idempotent even for expired tokens, so stale cookies can always be removed.
            post("/auth/logout") {
                val secret = settingsStore.settingsFlow.value.webServerJwtSecret
                sessions.revoke(extractBearerToken(call.request.headers[HttpHeaders.Authorization]), secret)
                sessions.revoke(call.request.cookies[WEB_ACCESS_TOKEN_COOKIE], secret)
                call.response.headers.append(HttpHeaders.SetCookie, webSessionCookie(secure = call.request.local.scheme == "https"))
                call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                call.respond(HttpStatusCode.OK, mapOf("status" to "logged_out"))
            }

            aiIconRoutes(context)

            authenticate("auth-jwt") {
                conversationRoutes(chatService, conversationRepo, folderRepo, webSettings, settingsStore)
                folderRoutes(chatService, folderRepo, webSettings)
                eventsRoutes(chatService, conversationRepo, folderRepo, webSettings, settingsStore)
                settingsRoutes(webSettings, settingsStore)
                configurationRoutes(webSettings, context, settingsStore)
                imageRoutes(settingsStore, filesManager)
                filesRoutes(filesManager, context)
                assetsRoutes(context)
            }
        }
    }
}

private fun extractBearerToken(authorizationHeader: String?): String? {
    if (authorizationHeader.isNullOrBlank()) return null
    val prefix = "Bearer "
    if (!authorizationHeader.startsWith(prefix, ignoreCase = true)) return null
    return authorizationHeader.substring(prefix.length).trim().takeIf { it.isNotEmpty() }
}

private fun extractAccessToken(authorizationHeader: String?, cookieToken: String?): String? {
    return extractBearerToken(authorizationHeader)
        ?: cookieToken?.trim()?.takeIf { it.isNotEmpty() }
}

private fun secureEquals(left: String, right: String): Boolean {
    return MessageDigest.isEqual(left.toByteArray(Charsets.UTF_8), right.toByteArray(Charsets.UTF_8))
}
