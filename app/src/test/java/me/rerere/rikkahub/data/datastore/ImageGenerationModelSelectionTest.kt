package me.rerere.rikkahub.data.datastore

import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ImageGenerationModelSelectionTest {
    @Test
    fun `standalone image page selection does not change chat image model`() {
        val chatModelId = Uuid.random()
        val pageModelId = Uuid.random()
        val settings = Settings(imageGenerationModelId = chatModelId)

        val updated = settings.copy(imageGenerationPageModelId = pageModelId)

        assertEquals(chatModelId, updated.imageGenerationModelId)
        assertEquals(pageModelId, updated.imageGenerationPageModelId)
        assertNotEquals(updated.imageGenerationModelId, updated.imageGenerationPageModelId)
    }

    @Test
    fun `clearing chat image model does not clear standalone image page selection`() {
        val pageModelId = Uuid.random()
        val settings = Settings(
            imageGenerationModelId = Uuid.random(),
            imageGenerationPageModelId = pageModelId,
        )

        val updated = settings.copy(imageGenerationModelId = Uuid.random())

        assertEquals(pageModelId, updated.imageGenerationPageModelId)
        assertNotEquals(updated.imageGenerationModelId, updated.imageGenerationPageModelId)
    }

    @Test
    fun `new standalone setting initially follows the existing image model`() {
        val existingModelId = Uuid.random()

        val settings = Settings(imageGenerationModelId = existingModelId)

        assertEquals(existingModelId, settings.imageGenerationPageModelId)
    }
}
