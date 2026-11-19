package me.rerere.rikkahub.web.routes

import android.graphics.BitmapFactory
import io.ktor.http.HttpHeaders
import io.ktor.http.content.PartData
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import me.rerere.ai.provider.*
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.data.db.entity.*
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.GenMediaRepository
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.web.*
import org.koin.core.context.GlobalContext
import java.io.File
import java.security.MessageDigest
import kotlin.uuid.Uuid

@Serializable
private data class ImageImport(val requestId: String, val modelId: String, val prompt: String, val editing: Boolean = false, val seed: Long? = null, val folderId: String? = null, val parameters: JsonObject? = null, val resultText: String? = null)
@Serializable
private data class ImageFolderWrite(val name: String = "", val folderId: String? = null)
private val imageImportLock = Mutex()

fun Route.imageRoutes(store: SettingsStore, files: FilesManager) {
    val repository = GlobalContext.get().get<GenMediaRepository>()
    val providers = GlobalContext.get().get<ProviderManager>()
    fun mediaJson(item: GenMediaEntity) = buildJsonObject {
        put("parameters", item.generationParameters?.let { JsonInstant.parseToJsonElement(it) } ?: JsonNull); put("resultText", item.resultText.orEmpty())
        put("id", item.id); put("prompt", item.prompt); put("model", item.modelId); put("provider", item.providerName.orEmpty())
        put("url", "/api/files/path/${item.path}"); put("createdAt", item.createAt); put("folderId", item.folderId?.let(::JsonPrimitive) ?: JsonNull)
        put("width", item.width?.let(::JsonPrimitive) ?: JsonNull); put("height", item.height?.let(::JsonPrimitive) ?: JsonNull)
        put("format", item.format.orEmpty()); put("seed", item.seed?.let(::JsonPrimitive) ?: JsonNull); put("type", item.type)
    }
    route("/images") {
        get("/models") {
            call.requireConfigurationLogin(store)
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            val settings = store.settingsFlow.value
            call.respond(buildJsonArray {
                settings.providers.filter { it.enabled }.flatMap { it.models }.filter { it.type == ModelType.IMAGE }.forEach { model ->
                    val provider = model.findProvider(settings.providers) ?: return@forEach
                    val constraints = providers.imageGenerationConstraints(provider, model)
                    if (constraints.supportsGeneration) add(buildJsonObject {
                        put("id", model.id.toString()); put("name", model.displayName.ifBlank { model.modelId }); put("modelId", model.modelId)
                        put("provider", provider.name); put("constraints", imageConstraintsJson(constraints))
                    })
                }
            })
        }
        // Credentials are supplied only on explicit generation, never via settings SSE or browser persistence.
        post("/models/{id}/connection") {
            call.requireConfigurationLogin(store)
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            val settings = store.settingsFlow.value
            val model = settings.providers.filter { it.enabled }.flatMap { it.models }.find { it.id.toString() == call.parameters["id"] && it.type == ModelType.IMAGE }
                ?: throw NotFoundException("图片模型不存在")
            val provider = model.findProvider(settings.providers) ?: throw NotFoundException("供应商不存在")
            if (provider is ProviderSetting.Google && provider.useServiceAccount) throw BadRequestException("此模型使用服务账号；浏览器直连请配置 API Key 供应商，私钥不会下发到浏览器")
            call.respond(buildJsonObject {
                when (provider) {
                    is ProviderSetting.OpenAI -> { put("type", "openai"); put("baseUrl", provider.baseUrl); put("apiKey", provider.apiKey.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()) }
                    is ProviderSetting.Google -> { put("type", "google"); put("baseUrl", provider.baseUrl); put("apiKey", provider.apiKey.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()); put("vertexAI", provider.vertexAI) }
                    else -> throw BadRequestException("供应商不支持浏览器图片生成")
                }
                put("modelId", model.modelId)
                put("customHeaders", JsonInstant.encodeToJsonElement(model.customHeaders)); put("customBodies", JsonInstant.encodeToJsonElement(model.customBodies))
            })
        }
        get {
            val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
            if (offset < 0) throw BadRequestException("分页参数无效")
            val items = repository.webPage(call.request.queryParameters["query"].orEmpty().take(500), call.request.queryParameters["folderId"], 40, offset)
            call.respond(buildJsonArray { items.forEach { add(mediaJson(it)) } })
        }
        post("/import") {
            call.requireConfigurationLogin(store)
            val multipart = call.receiveMultipart()
            var metadata: ImageImport? = null
            var bytes: ByteArray? = null
            while (true) {
                val part = multipart.readPart() ?: break
                try {
                    when (part) {
                        is PartData.FormItem -> if (part.name == "metadata") {
                            if (part.value.length > 128000 || metadata != null) throw BadRequestException("图片元数据无效")
                            metadata = JsonInstant.decodeFromString<ImageImport>(part.value)
                        }
                        is PartData.FileItem -> {
                            if (bytes != null) throw BadRequestException("每次仅能保存一张图片")
                            bytes = readPartBytes(part, 32 * 1024 * 1024)
                        }
                        else -> Unit
                    }
                } finally { part.dispose() }
            }
            val info = metadata ?: throw BadRequestException("缺少图片元数据")
            val data = bytes ?: throw BadRequestException("缺少图片内容")
            val requestId = info.requestId.toUuid("requestId")
            if ((info.resultText?.length ?: 0) > 64000 || (info.parameters?.toString()?.length ?: 0) > 32000) throw BadRequestException("图片参数或结果文字过长")
            if (info.prompt.length > 12000) throw BadRequestException("提示词过长")
            val model = store.settingsFlow.value.providers.flatMap { it.models }.find { it.id.toString() == info.modelId && it.type == ModelType.IMAGE }
                ?: throw BadRequestException("图片模型不存在")
            val item = withContext(Dispatchers.IO) { imageImportLock.withLock {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(data, 0, data.size, options)
                if (options.outWidth <= 0 || options.outHeight <= 0 || options.outMimeType !in setOf("image/png", "image/jpeg", "image/webp")) throw BadRequestException("仅支持有效 PNG、JPEG、WebP 图片")
                if (info.folderId != null && repository.getFolders().first().none { it.id == info.folderId }) throw BadRequestException("文件夹不存在")
                val extension = when (options.outMimeType) { "image/jpeg" -> "jpg"; "image/webp" -> "webp"; else -> "png" }
                val file = File(files.getImagesDir(), "web-$requestId.$extension")
                val path = "images/${file.name}"
                val existing = repository.byPath(path)
                if (existing != null) {
                    if (!file.isFile || !MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(file.readBytes()), MessageDigest.getInstance("SHA-256").digest(data))) throw ConflictException("保存标识已被不同图片使用")
                    existing
                } else {
                    file.parentFile?.mkdirs()
                    file.writeBytes(data)
                    try {
                        val entity = GenMediaEntity(path = path, modelId = model.modelId, prompt = info.prompt, createAt = System.currentTimeMillis(),
                            type = if (info.editing) GenMediaEntity.TYPE_IMAGE_EDIT else GenMediaEntity.TYPE_IMAGE_GENERATION,
                            providerName = model.findProvider(store.settingsFlow.value.providers)?.name, width = options.outWidth, height = options.outHeight,
                            format = extension, seed = info.seed, folderId = info.folderId,
                            generationParameters = info.parameters?.toString(), resultText = info.resultText)
                        entity.copy(id = repository.insertMedia(entity).toInt())
                    } catch (e: Throwable) { file.delete(); throw e }
                }
            } }
            call.respond(mediaJson(item))
        }
        delete("/{id}") {
            call.requireConfigurationLogin(store)
            val id = call.parameters["id"]?.toIntOrNull() ?: throw BadRequestException("图片标识无效")
            val item = repository.byId(id) ?: throw NotFoundException("图片不存在")
            if (!item.path.startsWith("images/") || item.path.contains("..")) throw BadRequestException("不是图片记录")
            repository.deleteMedia(id)
            withContext(Dispatchers.IO) { File(files.getImagesDir(), item.path.removePrefix("images/")).delete() }
            call.respond(mapOf("status" to "deleted"))
        }
        get("/folders") { call.respond(buildJsonArray { repository.getFolders().first().forEach { add(buildJsonObject { put("id", it.id); put("name", it.name) }) } }) }
        post("/folders") {
            call.requireConfigurationLogin(store)
            val request = call.receive<ImageFolderWrite>()
            if (request.name.isBlank() || request.name.length > 100) throw BadRequestException("文件夹名称无效")
            repository.createFolder(GenMediaFolderEntity(Uuid.random().toString(), request.name.trim(), System.currentTimeMillis()))
            call.respond(mapOf("status" to "created"))
        }
        delete("/folders/{id}") {
            call.requireConfigurationLogin(store)
            repository.dissolveFolder(call.parameters["id"]!!)
            call.respond(mapOf("status" to "dissolved"))
        }
        post("/{id}/folder") {
            call.requireConfigurationLogin(store)
            val request = call.receive<ImageFolderWrite>()
            if (request.folderId != null && repository.getFolders().first().none { it.id == request.folderId }) throw BadRequestException("文件夹不存在")
            repository.moveMediaToFolder(call.parameters["id"]?.toIntOrNull() ?: throw BadRequestException("图片标识无效"), request.folderId)
            call.respond(mapOf("status" to "moved"))
        }
    }
}
