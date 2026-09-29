package me.rerere.ai.provider

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderSettingOAuthTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `oauth provider discriminators remain stable`() {
        val encoded = listOf<ProviderSetting>(
            ProviderSetting.Codex(),
            ProviderSetting.Grok(),
            ProviderSetting.GeminiOAuth(),
        ).map { json.encodeToString(ProviderSetting.serializer(), it) }

        assertTrue(encoded[0].contains("\"type\":\"codex\""))
        assertTrue(encoded[1].contains("\"type\":\"grok\""))
        assertTrue(encoded[2].contains("\"type\":\"gemini_oauth\""))
    }

    @Test
    fun `oauth providers are not offered as generic api-key conversions`() {
        assertFalse(ProviderSetting.Types.contains(ProviderSetting.Codex::class))
        assertFalse(ProviderSetting.Types.contains(ProviderSetting.Grok::class))
        assertFalse(ProviderSetting.Types.contains(ProviderSetting.GeminiOAuth::class))
    }
}
