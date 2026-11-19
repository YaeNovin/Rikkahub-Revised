package me.rerere.ai.provider.providers.claude

import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.util.json
import org.junit.Assert.*
import org.junit.Test

class ClaudeApiVersionTest {
    @Test fun `old configurations preserve their API version`() {
        assertEquals("2023-06-01", json.decodeFromString<ProviderSetting.Claude>("{}").resolvedAnthropicVersion())
    }
    @Test fun `official version can be configured without changing gateway defaults`() {
        assertEquals("2026-10-21", ProviderSetting.Claude(apiVersion = "2026-10-21").resolvedAnthropicVersion())
        assertEquals("2023-06-01", ProviderSetting.Claude(baseUrl = "https://gateway.example/v1", apiVersion = "2026-10-21").resolvedAnthropicVersion())
    }
    @Test(expected = IllegalArgumentException::class) fun `malformed version fails before network`() {
        ProviderSetting.Claude(apiVersion = "invalid\r\nheader").resolvedAnthropicVersion()
    }
}
