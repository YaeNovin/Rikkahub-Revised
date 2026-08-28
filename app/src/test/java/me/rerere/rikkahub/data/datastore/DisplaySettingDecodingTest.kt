package me.rerere.rikkahub.data.datastore

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplaySettingDecodingTest {
    @Test
    fun `invalid display data falls back without breaking settings`() {
        assertEquals(DisplaySetting(), decodeDisplaySetting("not-json"))
    }
}
