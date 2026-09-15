package me.rerere.tts.provider.providers

import me.rerere.tts.provider.TTSProviderSetting
import org.junit.Assert.*
import org.junit.Test

class MiniMaxSpeechDecoderTest {
    @Test fun officialResponseWithoutCedIsAccepted() {
        val d = MiniMaxSpeechDecoder(TTSProviderSetting.MiniMax())
        val chunk = d.decode("""{"data":{"audio":"010203","status":2,"subtitle_file":"https://example.com/subtitle.json"},"base_resp":{"status_code":0}}""")
        assertArrayEquals(byteArrayOf(1,2,3), chunk!!.data)
        assertEquals("https://example.com/subtitle.json", chunk.metadata["subtitle_url"])
        d.finish()
    }
    @Test(expected = IllegalStateException::class) fun providerErrorIsNotSilentlyDiscarded() {
        MiniMaxSpeechDecoder(TTSProviderSetting.MiniMax()).decode("""{"data":null,"base_resp":{"status_code":1004,"status_msg":"authentication failed"}}""")
    }
    @Test(expected = IllegalStateException::class) fun truncatedStreamIsAnError() {
        val d = MiniMaxSpeechDecoder(TTSProviderSetting.MiniMax())
        d.decode("""{"data":{"audio":"0102","status":1}}""")
        d.finish()
    }
}
