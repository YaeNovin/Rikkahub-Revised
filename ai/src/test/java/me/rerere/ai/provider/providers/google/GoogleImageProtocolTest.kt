package me.rerere.ai.provider.providers.google

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.ImageGenerationConstraints
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleImageProtocolTest {
    private val constraints = ImageGenerationConstraints(
        supportsGeneration = true,
        supportsEdit = true,
        supportsPartialImages = false,
        supportedSizes = GOOGLE_EXTENDED_IMAGE_ASPECT_RATIOS,
        supportsCustomSize = false,
        supportedResolutionValues = GOOGLE_31_IMAGE_RESOLUTIONS,
    )

    @Test
    fun `uses documented aspect ratios and resolution values per Gemini image family`() {
        val provider = GoogleProvider(OkHttpClient())
        val setting = ProviderSetting.Google()
        val flash31 = provider.imageGenerationConstraints(
            setting,
            Model(modelId = "gemini-3.1-flash-image"),
        )
        val pro3 = provider.imageGenerationConstraints(
            setting,
            Model(modelId = "gemini-3-pro-image"),
        )
        val flash25 = provider.imageGenerationConstraints(
            setting,
            Model(modelId = "gemini-2.5-flash-image"),
        )

        assertTrue("1:8" in flash31.supportedSizes.orEmpty())
        assertEquals(linkedSetOf("512", "1K", "2K", "4K"), flash31.supportedResolutionValues)
        assertFalse("1:8" in pro3.supportedSizes.orEmpty())
        assertEquals(linkedSetOf("1K", "2K", "4K"), pro3.supportedResolutionValues)
        assertEquals(emptySet<String>(), flash25.supportedResolutionValues)
        assertEquals("aspect_ratio", flash31.sizeRequestField)
    }

    @Test
    fun `builds Gemini image generation and edit request`() {
        val imagePart = Json.parseToJsonElement(
            """{"inlineData":{"mimeType":"image/png","data":"reference"}}"""
        ).jsonObject

        val body = buildGoogleImageRequestBody(
            prompt = "Draw a lighthouse",
            size = "16:9",
            resolution = "2K",
            customBody = emptyList(),
            constraints = constraints,
            imageParts = listOf(imagePart),
        )

        val content = body["contents"]!!.jsonArray.single().jsonObject
        val parts = content["parts"]!!.jsonArray
        val generationConfig = body["generationConfig"]!!.jsonObject
        val imageConfig = generationConfig["imageConfig"]!!.jsonObject
        assertEquals("user", content["role"]!!.jsonPrimitive.content)
        assertEquals("Draw a lighthouse", parts[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("reference", parts[1].jsonObject["inlineData"]!!.jsonObject["data"]!!.jsonPrimitive.content)
        assertEquals(listOf("TEXT", "IMAGE"), generationConfig["responseModalities"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("16:9", imageConfig["aspectRatio"]!!.jsonPrimitive.content)
        assertEquals("2K", imageConfig["imageSize"]!!.jsonPrimitive.content)
    }

    @Test
    fun `omits unsupported options and ignores thought images`() {
        val body = buildGoogleImageRequestBody(
            prompt = "Draw a lighthouse",
            size = "1024x1024",
            resolution = "8K",
            customBody = emptyList(),
            constraints = constraints,
            imageParts = emptyList(),
        )
        assertFalse(body["generationConfig"]!!.jsonObject.containsKey("imageConfig"))

        val response = Json.parseToJsonElement(
            """
            {"candidates":[{"content":{"parts":[
              {"thought":true,"inlineData":{"mimeType":"image/png","data":"draft"}},
              {"inlineData":{"mimeType":"image/webp","data":"final"}},
              {"text":"Done"}
            ]}}]}
            """.trimIndent()
        ).jsonObject
        val images = parseGoogleGeneratedImages(response)

        assertEquals(1, images.size)
        assertEquals("final", images.single().data)
        assertEquals("image/webp", images.single().mimeType)
        assertTrue(images.none { it.data == "draft" })
    }
}
