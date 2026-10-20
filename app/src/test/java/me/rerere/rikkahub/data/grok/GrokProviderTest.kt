package me.rerere.rikkahub.data.grok

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderQuota
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

class GrokProviderTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `model catalogs prefer cli metadata and retain imagine models`() {
        val models = parseGrokModels(
            cliCatalog = json.parseToJsonElement(
                """{"data":[
                    {"model":"grok-4.6","name":"Grok 4.6","reasoningEfforts":[{"value":"low"}],"contextWindow":1000000},
                    {"model":"grok-imagine-image","name":"Imagine"},
                    {"model":"grok-secret","hidden":true}
                ]}"""
            ).jsonObject,
            apiCatalog = json.parseToJsonElement(
                """{"data":[{"id":"grok-4.6"},{"id":"grok-4.5"},{"id":"grok-imagine-video"}]}"""
            ).jsonObject,
        )

        assertEquals(listOf("grok-4.6", "grok-imagine-image", "grok-4.5"), models.map { it.modelId })
        assertTrue(ModelAbility.REASONING in models[0].abilities)
        assertEquals(1_000_000, models[0].contextLength)
        assertEquals(me.rerere.ai.provider.ModelType.IMAGE, models[1].type)
        assertFalse(ModelAbility.REASONING in models[2].abilities)
    }

    @Test
    fun `reasoning effort is omitted or constrained by model`() {
        assertNull(grokReasoningEffort(ReasoningLevel.HIGH, "grok-4.20-0309-reasoning"))
        assertEquals("none", grokReasoningEffort(ReasoningLevel.OFF, "grok-4.3"))
        assertEquals("high", grokReasoningEffort(ReasoningLevel.MAX, "grok-4.3"))
        assertEquals("xhigh", grokReasoningEffort(ReasoningLevel.MAX, "grok-4.6"))
    }

    @Test
    fun `weekly quota parses active shared pool`() {
        val quota = parseGrokWeeklyQuota(
            json.parseToJsonElement(
                """{"config":{"creditUsagePercent":"37.5","currentPeriod":{"start":"2026-10-13T00:00:00Z","end":"2026-10-20T00:00:00Z","type":"USAGE_PERIOD_TYPE_WEEKLY"}}}"""
            ).jsonObject,
            now = Instant.parse("2026-10-19T00:00:00Z"),
        )

        assertEquals(
            ProviderQuota.UsageLimits(
                windows = listOf(ProviderQuotaWindow(37.5, 10_080, Instant.parse("2026-10-20T00:00:00Z"))),
                limitReached = false,
            ),
            quota,
        )
    }

    @Test
    fun `monthly quota parses unified billing period`() {
        val quota = parseGrokMonthlyQuota(
            json.parseToJsonElement(
                """{"config":{"billingPeriodStart":"2026-10-01T00:00:00Z","billingPeriodEnd":"2026-10-31T00:00:00Z","monthlyLimit":{"val":"2000"},"used":{"val":1000}}}"""
            ).jsonObject,
        )

        assertEquals(50.0, quota!!.windows.single().usedPercent, 0.0)
        assertEquals(43_200, quota.windows.single().windowMinutes)
        assertFalse(quota.limitReached)
    }
}

private typealias ProviderQuotaWindow = me.rerere.ai.provider.UsageWindow
