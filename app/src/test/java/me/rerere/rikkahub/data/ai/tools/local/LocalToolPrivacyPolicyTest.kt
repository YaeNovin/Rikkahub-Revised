package me.rerere.rikkahub.data.ai.tools.local

import android.content.ContextWrapper
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.rikkahub.data.event.AppEventBus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

class LocalToolPrivacyPolicyTest {
    private val context = ContextWrapper(null)

    @Test
    fun `sensitive local tools require explicit approval`() {
        assertTrue(buildClipboardTool(context).needsApproval(JsonNull))
        assertTrue(buildCalendarDeleteTool(context).needsApproval(JsonNull))
        assertTrue(buildScreenTimeTool(context, AppEventBus()).needsApproval(JsonNull))
    }

    @Test
    fun `calendar deletion accepts only positive event identifiers`() {
        assertEquals(42L, parseCalendarEventId("42"))
        assertEquals(42L, parseCalendarEventId(" 42 "))
        assertEquals(null, parseCalendarEventId("0"))
        assertEquals(null, parseCalendarEventId("-1"))
        assertEquals(null, parseCalendarEventId("event-42"))

        val schema = buildCalendarDeleteTool(context).parameters() as InputSchema.Obj
        assertTrue(schema.required?.contains("event_id") == true)
        assertEquals("integer", schema.properties["event_id"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `screen time defaults to aggregate only and caps disclosed detail`() {
        assertFalse(screenTimeIncludesAppDetails(buildJsonObject {}))
        assertTrue(screenTimeIncludesAppDetails(buildJsonObject { put("include_apps", true) }))
        assertEquals(MAX_SCREEN_TIME_APP_DETAILS, screenTimeAppDetailsLimit(buildJsonObject {}))
        assertEquals(MAX_SCREEN_TIME_APP_DETAILS, screenTimeAppDetailsLimit(buildJsonObject { put("top", 99) }))
        assertEquals(1, screenTimeAppDetailsLimit(buildJsonObject { put("top", -1) }))
    }

    @Test
    fun `screen time rejects ranges longer than seven days`() {
        val start = ZonedDateTime.of(2026, 8, 1, 0, 0, 0, 0, ZoneOffset.UTC)

        assertTrue(isScreenTimeRangeAllowed(start, start.plusDays(MAX_SCREEN_TIME_RANGE_DAYS)))
        assertFalse(isScreenTimeRangeAllowed(start, start.plusDays(MAX_SCREEN_TIME_RANGE_DAYS).plusSeconds(1)))
    }
}
