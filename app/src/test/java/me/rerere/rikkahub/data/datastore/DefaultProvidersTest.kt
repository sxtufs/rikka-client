package me.rerere.rikkahub.data.datastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultProvidersTest {
    @Test
    fun `default providers exclude removed third party gateways`() {
        val removedNames = setOf(
            "AiHubMix",
            "APIMart",
            "硅基流动",
            "月之暗面",
            "Vercel AI Gateway",
            "阿里云百炼",
            "火山引擎",
            "智谱AI开放平台",
            "阶跃星辰",
            "302.AI",
            "腾讯Hunyuan",
            "随想AI网关",
            "MaruCode",
            "MiniMax",
            "MIMO",
        )

        assertTrue(DEFAULT_PROVIDERS.none { it.name in removedNames })
    }

    @Test
    fun `protected providers remain in default providers`() {
        val names = DEFAULT_PROVIDERS.map { it.name }.toSet()

        assertTrue("Codex" in names)
        assertTrue("Gemini OAuth" in names)
        assertTrue("Grok" in names)
        assertTrue("DeepSeek" in names)
        assertTrue("OpenRouter" in names)
        assertEquals(1, DEFAULT_PROVIDERS.count { it.name == "DeepSeek" })
        assertEquals(1, DEFAULT_PROVIDERS.count { it.name == "OpenRouter" })
    }
}
