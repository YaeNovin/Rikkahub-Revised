package me.rerere.rikkahub

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.GenMediaRepository
import me.rerere.rikkahub.web.WebServerManager
import me.rerere.rikkahub.web.WebSettings
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

class WebImageImportDeviceTest {
    @Test fun browserResultImportIsIdempotentAndStoredInNativeGallery() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".revised.websettingsqa"))
        val native = GlobalContext.get().get<SettingsStore>()
        val web = GlobalContext.get().get<WebSettings>().store
        val manager = GlobalContext.get().get<WebServerManager>()
        val repository = GlobalContext.get().get<GenMediaRepository>()
        val original = withTimeout(15000) { native.settingsFlow.first { !it.init } }
        val originalWeb = withTimeout(15000) { web.settingsFlow.first { !it.init } }
        val originalServer = manager.state.value
        val model = Model(modelId = "gpt-image-1", type = ModelType.IMAGE)
        val provider = ProviderSetting.OpenAI(name = "Web image device fixture", baseUrl = "https://mock.invalid/v1", apiKey = "fixture-key", models = listOf(model))
        val password = Uuid.random().toString()
        val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
        var token = ""
        var inserted: Int? = null
        fun request(path: String, body: RequestBody? = null, authenticated: Boolean = true): Pair<Int, String> {
            val builder = Request.Builder().url("http://127.0.0.1:18088/api/$path")
            if (authenticated) builder.header("Authorization", "Bearer $token")
            if (body != null) builder.post(body)
            return client.newCall(builder.build()).execute().use { it.code to it.body.string() }
        }
        fun jsonBody(value: JsonElement) = value.toString().toRequestBody("application/json".toMediaType())
        try {
            native.update { it.copy(providers = it.providers + provider, webServerJwtEnabled = true, webServerAccessPassword = password) }
            manager.restart(port = 18088, localhostOnly = true)
            withTimeout(15000) { manager.state.first { it.isRunning && it.port == 18088 && !it.isLoading } }
            val login = request("auth/token", jsonBody(buildJsonObject { put("password", password) }), false)
            assertEquals(200, login.first)
            token = Json.parseToJsonElement(login.second).jsonObject.getValue("token").jsonPrimitive.content
            val models = request("images/models")
            assertEquals(200, models.first)
            val selected = Json.parseToJsonElement(models.second).jsonArray.first { it.jsonObject["id"]?.jsonPrimitive?.content == model.id.toString() }.jsonObject
            assertTrue(selected.getValue("constraints").jsonObject.getValue("supportsEdit").jsonPrimitive.boolean)
            assertFalse(models.second.contains("fixture-key"))
            val connection = request("images/models/${model.id}/connection", jsonBody(buildJsonObject {}))
            assertEquals(200, connection.first)
            assertEquals("fixture-key", Json.parseToJsonElement(connection.second).jsonObject["apiKey"]?.jsonPrimitive?.content)
            assertEquals(403, request("settings/manage/security", jsonBody(buildJsonObject {})).first)
            val preference = request("settings/manage/preferences")
            val revision = Json.parseToJsonElement(preference.second).jsonObject.getValue("revision")
            assertEquals(200, request("settings/manage/preferences", jsonBody(buildJsonObject { put("revision", revision); put("values", buildJsonObject { put("sendOnEnter", !original.displaySetting.sendOnEnter) }) })).first)
            assertEquals(original.displaySetting.sendOnEnter, native.settingsFlowRaw.first().displaySetting.sendOnEnter)
            val bytes = ByteArrayOutputStream().use { output ->
                val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.BLUE)
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                bitmap.recycle(); output.toByteArray()
            }
            val metadata = buildJsonObject { put("requestId", Uuid.random().toString()); put("modelId", model.id.toString()); put("prompt", "Web browser fixture"); put("parameters", buildJsonObject { put("size", "1024x1024"); put("count", 1) }); put("resultText", "Supplier revised prompt") }
            fun importBody() = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("metadata", metadata.toString())
                .addFormDataPart("image", "result.png", bytes.toRequestBody("image/png".toMediaType())).build()
            val first = request("images/import", importBody())
            assertEquals(200, first.first)
            inserted = Json.parseToJsonElement(first.second).jsonObject.getValue("id").jsonPrimitive.int
            val second = request("images/import", importBody())
            assertEquals(200, second.first)
            assertEquals(inserted, Json.parseToJsonElement(second.second).jsonObject.getValue("id").jsonPrimitive.int)
            val record = repository.byId(inserted!!)
            assertNotNull(record)
            assertEquals("Web browser fixture", record!!.prompt)
            assertEquals("1024x1024", Json.parseToJsonElement(record.generationParameters!!).jsonObject.getValue("size").jsonPrimitive.content)
            assertEquals("Supplier revised prompt", record.resultText)
            val gallery = request("images")
            assertEquals(200, gallery.first)
            val saved = Json.parseToJsonElement(gallery.second).jsonArray.first { it.jsonObject["id"]?.jsonPrimitive?.int == inserted }.jsonObject
            assertEquals("Supplier revised prompt", saved.getValue("resultText").jsonPrimitive.content)
            assertEquals("1024x1024", saved.getValue("parameters").jsonObject.getValue("size").jsonPrimitive.content)
            assertEquals(2, record.width)
            assertEquals(2, record.height)
            assertEquals("gpt-image-1", record.modelId)
            assertEquals("image_generation", record.type)
        } finally {
            inserted?.let { id ->
                val item = repository.byId(id)
                repository.deleteMedia(id)
                item?.let { java.io.File(context.filesDir, it.path).delete() }
            }
            manager.stop()
            withTimeout(10000) { manager.state.first { !it.isRunning && !it.isLoading } }
            native.update(original); web.update(originalWeb)
            if (originalServer.isRunning) manager.start(port = originalServer.port, localhostOnly = originalServer.localhostOnly)
            client.dispatcher.executorService.shutdown()
        }
    }
}
