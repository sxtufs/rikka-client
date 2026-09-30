package me.rerere.rikkahub.data.grok

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class GrokJsonTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `identity falls back safely when the access token is not a JWT`() {
        val identity = parseGrokIdentity("access-token-without-jwt", json)

        assertEquals("", identity.userId)
        assertEquals("", identity.email)
        assertEquals("Grok", identity.name)
    }

    @Test
    fun `credits parser reads weekly usage and on demand cap`() {
        val root = buildJsonObject {
            put("config", buildJsonObject {
                put("currentPeriod", buildJsonObject {
                    put("type", "USAGE_PERIOD_TYPE_WEEKLY")
                    put("start", "2026-01-01T00:00:00Z")
                    put("end", "2026-01-08T00:00:00Z")
                })
                put("creditUsagePercent", 42.5)
                put("onDemandCap", buildJsonObject { put("val", 12.0) })
            })
        }

        val usage = parseGrokCreditsUsage(root)

        assertNotNull(usage.weekly)
        assertEquals(42.5, usage.weekly?.usedPercent ?: 0.0, 0.0)
        assertEquals(12.0, usage.onDemandCap, 0.0)
    }

    @Test
    fun `token-only identity gets a stable non-secret account id`() {
        val identity = GrokIdentity(userId = "", email = "", name = "Grok")

        val first = stableGrokAccountId(identity, "refresh-token", "access-token-1")
        val second = stableGrokAccountId(identity, "refresh-token", "access-token-2")

        assertEquals(first, second)
        org.junit.Assert.assertTrue(first.startsWith("grok-"))
        org.junit.Assert.assertFalse(first.contains("refresh-token"))
    }
}
