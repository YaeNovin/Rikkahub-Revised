package me.rerere.rikkahub.web.routes

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.McpStatus
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.files.SkillFrontmatterParser
import me.rerere.rikkahub.service.WebServerService
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.web.*
import me.rerere.rikkahub.web.dto.*
import org.koin.core.context.GlobalContext

internal fun ApplicationCall.requireConfigurationLogin(store: SettingsStore) {
    if (!store.settingsFlow.value.webServerJwtEnabled || principal<JWTPrincipal>() == null || request.headers[HttpHeaders.Authorization]?.startsWith("Bearer ", ignoreCase = true) != true) {
        throw ForbiddenException("请先在手机开启 Web 密码保护并登录，再管理供应商、服务、Skills 或访问配置")
    }
}

fun Route.configurationRoutes(webStore: SettingsStore, context: Context, settingsStore: SettingsStore = webStore) {
    route("/settings/manage") {
        get("/catalog") {
            call.requireConfigurationLogin(settingsStore)
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            call.respond(configurationTemplates())
        }
        get("/runtime") {
            val manager = GlobalContext.get().get<WebServerManager>()
            val state = manager.state.value
            call.respond(buildJsonObject {
                put("running", state.isRunning); put("port", state.port); put("localhostOnly", state.localhostOnly)
                put("address", state.address); put("hostname", state.hostname)
                put("localNetworkPermission", Build.VERSION.SDK_INT < 37 || context.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") == PackageManager.PERMISSION_GRANTED)
                put("batteryOptimizationsIgnored", context.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName))
                put("backgroundRestricted", Build.VERSION.SDK_INT >= 28 && context.getSystemService(android.app.ActivityManager::class.java).isBackgroundRestricted)
                put("manufacturer", Build.MANUFACTURER)
            })
        }
        get("/{group}") {
            val group = call.parameters["group"]!!
            if (group == "security") throw ForbiddenException("服务端口、密码与监听范围请在手机管理；Web 安全偏好独立保存在浏览器")
            if (group !in setOf("preferences", "conversations", "assistant")) call.requireConfigurationLogin(settingsStore)
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            val store = if (group in setOf("providers", "search", "mcp")) settingsStore else webStore
            call.respond(configurationDocument(store.settingsFlow.value, group, call.request.queryParameters["assistantId"]))
        }
        post("/{group}") {
            val group = call.parameters["group"]!!
            if (group == "security") throw ForbiddenException("请在手机管理 Web 服务访问控制")
            if (group !in setOf("preferences", "conversations")) call.requireConfigurationLogin(settingsStore)
            val request = call.receive<ConfigurationUpdate>()
            if (request.values.toString().length > 1_000_000) throw BadRequestException("配置内容过大")
            val assistantId = call.request.queryParameters["assistantId"]
            var savedDocument: JsonObject? = null
            val store = if (group in setOf("providers", "search", "mcp")) settingsStore else webStore
            store.update { current ->
                val next = applyConfiguration(current, group, request, assistantId)
                if (group == "assistant") {
                    val names = GlobalContext.get().get<SkillManager>().listSkills().map { it.name }.toSet()
                    if (next.assistants.first { it.id.toString() == assistantId }.enabledSkills.any { it !in names }) throw BadRequestException("选择的 Skill 已不存在")
                }
                // Capture under the update lock. The live flow may already carry a
                // different emission by the time disk persistence completes.
                savedDocument = configurationDocument(next, group, assistantId)
                next
            }
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            call.respond(checkNotNull(savedDocument))
        }
        post("/providers/discover") {
            call.requireConfigurationLogin(settingsStore)
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            val provider = configurationProviderDraft(settingsStore.settingsFlow.value, call.receive<JsonObject>())
            val manager = GlobalContext.get().get<ProviderManager>()
            val models = try { withTimeout(30_000) { manager.getProviderByType(provider).listModels(provider) } }
                catch (_: Exception) { throw BadRequestException("获取模型失败，请检查供应商地址和凭据") }
            call.respond(configurationPublic(JsonInstant.encodeToJsonElement(models.filter { it.type != ModelType.VIDEO })))
        }
        get("/providers/{id}/models") {
            call.requireConfigurationLogin(settingsStore)
            val provider = settingsStore.settingsFlow.value.providers.find { it.id.toString() == call.parameters["id"] } ?: throw NotFoundException("供应商不存在，请先保存")
            val manager = GlobalContext.get().get<ProviderManager>()
            val models = try { withTimeout(30_000) { manager.getProviderByType(provider).listModels(provider) } }
                catch (_: Exception) { throw BadRequestException("获取模型失败，请检查供应商地址和凭据") }
            call.respond(configurationPublic(JsonInstant.encodeToJsonElement(models.filter { it.type in setOf(ModelType.CHAT, ModelType.EMBEDDING, ModelType.IMAGE) })))
        }
        get("/mcp/status") {
            call.requireConfigurationLogin(settingsStore)
            val manager = GlobalContext.get().get<McpManager>()
            call.respond(buildJsonObject { manager.syncingStatus.value.forEach { (id, status) ->
                put(id.toString(), when (status) { McpStatus.Connected -> "已连接"; McpStatus.Connecting -> "连接中"; McpStatus.NeedsAuthorization -> "需在手机完成 OAuth 授权"; McpStatus.Authorizing -> "授权中"; is McpStatus.Error -> "连接失败，请检查地址和凭据"; is McpStatus.Reconnecting -> "重连中"; else -> "未连接" })
            } })
        }
        post("/mcp/sync") {
            call.requireConfigurationLogin(settingsStore)
            GlobalContext.get().get<McpManager>().syncAll()
            call.respond(mapOf("status" to "ok"))
        }
        skillConfigurationRoutes(settingsStore)
    }
}

@Serializable
private data class SkillWrite(val name: String, val content: String, val revision: String)

private fun Route.skillConfigurationRoutes(store: SettingsStore) {
    fun manager() = GlobalContext.get().get<SkillManager>()
    fun validName(name: String) { if (name.isBlank() || name in setOf(".", "..") || name.toByteArray().size > 255 || name.any { it == '/' || it == '\\' || it == '\u0000' }) throw BadRequestException("Skill 名称无效，不能包含路径分隔符") }
    get("/skills") {
        call.requireConfigurationLogin(store)
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.respond(withContext(Dispatchers.IO) { buildJsonArray { manager().listSkills().forEach { skill -> add(buildJsonObject { put("name", skill.name); put("description", skill.description) }) } } })
    }
    get("/skills/{name}") {
        call.requireConfigurationLogin(store)
        val name = call.parameters["name"]!!; validName(name)
        val content = withContext(Dispatchers.IO) { manager().readSkillContent(name) } ?: throw NotFoundException("Skill 不存在")
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.respond(buildJsonObject { put("name", name); put("content", content); put("revision", configurationRevision(JsonPrimitive(content))) })
    }
    post("/skills/save") {
        call.requireConfigurationLogin(store)
        val request = call.receive<SkillWrite>(); validName(request.name)
        if (request.content.toByteArray().size > 512_000) throw BadRequestException("Skill 文本不能超过 512KB")
        val metadata = SkillFrontmatterParser.parse(request.content)
        if (metadata["name"] != request.name || metadata["description"].isNullOrBlank()) throw BadRequestException("SKILL.md 的 name 必须与名称一致，并提供 description")
        withContext(Dispatchers.IO) {
            val skills = manager()
            synchronized(skills) {
                val old = skills.readSkillContent(request.name)
                if (request.revision != configurationRevision(JsonPrimitive(old ?: ""))) throw ConflictException("Skill 已改变，请重新加载后重试")
                val saved = if (old != null) skills.saveSkillFile(request.name, "SKILL.md", request.content) else skills.saveSkill(request.name, request.content) != null
                if (!saved) throw BadRequestException("保存 Skill 失败")
            }
        }
        call.respond(buildJsonObject { put("revision", configurationRevision(JsonPrimitive(request.content))) })
    }
    get("/skills-new") {
        call.requireConfigurationLogin(store)
        call.respond(mapOf("revision" to configurationRevision(JsonPrimitive(""))))
    }
    post("/skills/delete") {
        call.requireConfigurationLogin(store)
        val request = call.receive<SkillWrite>(); validName(request.name)
        val old = withContext(Dispatchers.IO) { manager().readSkillContent(request.name) } ?: throw NotFoundException("Skill 不存在")
        if (request.revision != configurationRevision(JsonPrimitive(old))) throw ConflictException("Skill 已改变，请重新加载后重试")
        if (!manager().deleteSkill(request.name, expectedContent = old)) throw ConflictException("删除失败或内容已改变，请重新加载")
        call.respond(mapOf("status" to "ok"))
    }
}
