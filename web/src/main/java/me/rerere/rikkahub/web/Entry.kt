package me.rerere.rikkahub.web

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.singlePageApplication
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.routing.routing
import io.ktor.server.response.respond
import io.ktor.server.sse.SSE

fun startWebServer(
    port: Int = 8080,
    host: String = "0.0.0.0",
    module: suspend Application.() -> Unit
): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> {
    return embeddedServer(CIO, port = port, host = host, module = {
        // Sandboxed srcdoc previews have an opaque origin. Deny before CORS and
        // routing, even with JWT disabled: CORS alone must not allow a preview
        // to read conversations or perform a simple/form API mutation.
        intercept(ApplicationCallPipeline.Setup) {
            if (call.request.headers[HttpHeaders.Origin]?.trim() == "null") {
                call.respond(HttpStatusCode.Forbidden)
                finish()
            }
        }
        install(Compression)
        install(CORS) {
            allowHeader(HttpHeaders.ContentType)
            allowHeader(HttpHeaders.Authorization)
            allowNonSimpleContentTypes = true
            anyHost()
            anyMethod()
        }
        install(SSE)
        install(DefaultHeaders)
        routing {
            staticResources("/", "static") {
                default("index.html")
                enableAutoHeadResponse()
                singlePageApplication()
            }
        }
        module()
    })
}
