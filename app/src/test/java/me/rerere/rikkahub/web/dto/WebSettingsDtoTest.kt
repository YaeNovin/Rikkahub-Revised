package me.rerere.rikkahub.web.dto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import org.junit.Assert.*
import org.junit.Test

class WebSettingsDtoTest {
    @Test fun `persisted settings keep secrets but browser response excludes them`() {
        val assistant = Assistant(name = "Visible assistant", avatar = Avatar.Emoji("🍀"), systemPrompt = "private-system-sentinel")
        val settings = Settings(
            assistants = listOf(assistant), assistantId = assistant.id,
            webServerJwtEnabled = true, webServerAccessPassword = "password-sentinel",
            webServerJwtSecret = "signing-sentinel",
        )
        val dto = settings.toWebSettingsDto()
        assertEquals("password-sentinel", settings.webServerAccessPassword)
        assertFalse(dto.toString().contains("sentinel"))
        assertEquals("true", dto.getValue("webServerJwtEnabled").jsonPrimitive.content)
        val profile = dto.getValue("assistants").jsonArray.single().jsonObject
        assertEquals("Visible assistant", profile.getValue("name").jsonPrimitive.content)
        assertEquals("🍀", profile.getValue("avatar").jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals(assistant.id.toString(), dto.getValue("assistantId").jsonPrimitive.content)
    }

    @Test fun `every nested profile is allowlisted including future credentials`() {
        val source = Json.parseToJsonElement("""
            {
              "assistantId":"a", "webServerAccessPassword":"password-sentinel",
              "futureSecret":"future-sentinel", "webDavConfig":{"password":"dav-sentinel"},
              "s3Config":{"secretKey":"s3-sentinel"},
              "providers":[{"id":"p","name":"Visible","enabled":true,"apiKey":"key-sentinel",
                "privateKey":"private-sentinel","models":[{"id":"m","modelId":"model","type":"CHAT",
                  "customHeaders":[{"value":"header-sentinel"}],"tools":[{"type":"search","secret":"tool-sentinel"}]}]}],
              "mcpServers":[{"id":"s","headers":{"Authorization":"mcp-sentinel"},
                "oauth":{"accessToken":"oauth-sentinel"},"commonOptions":{"enable":true,"name":"Tools",
                  "futureSecret":"nested-sentinel","tools":[{"name":"tool","enable":true,"needsApproval":true,"secret":"tool-sentinel"}]}}],
              "searchServices":[{"id":"search","type":"tavily","apiKey":"search-sentinel"}],
              "quickMessages":[{"id":"q","title":"Quick","content":"User text","futureSecret":"quick-sentinel"}]
            }
        """).jsonObject
        val dto = projectWebSettings(source)
        assertFalse(dto.toString().contains("sentinel"))
        assertEquals(setOf("assistantId", "providers", "mcpServers", "searchServices", "quickMessages"), dto.keys)
        val provider = dto.getValue("providers").jsonArray.single().jsonObject
        assertEquals(setOf("id", "name", "enabled", "models"), provider.keys)
        val model = provider.getValue("models").jsonArray.single().jsonObject
        assertEquals(setOf("id", "modelId", "type", "tools"), model.keys)
        assertEquals("search", model.getValue("tools").jsonArray.single().jsonObject.getValue("type").jsonPrimitive.content)
        val server = dto.getValue("mcpServers").jsonArray.single().jsonObject
        assertEquals(setOf("id", "commonOptions"), server.keys)
        assertEquals("true", server.getValue("commonOptions").jsonObject.getValue("tools").jsonArray.single().jsonObject.getValue("needsApproval").jsonPrimitive.content)
        assertEquals("User text", dto.getValue("quickMessages").jsonArray.single().jsonObject.getValue("content").jsonPrimitive.content)
    }
}
