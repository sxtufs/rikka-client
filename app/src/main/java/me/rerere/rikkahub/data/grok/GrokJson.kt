package me.rerere.rikkahub.data.grok

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.ProviderQuota
import me.rerere.ai.provider.UsageWindow
import kotlin.time.Clock
import kotlin.time.Instant
import java.time.OffsetDateTime
import java.util.Base64

internal data class GrokIdentity(
    val userId: String,
    val email: String,
    val name: String,
)

/**
 * xAI OAuth access/id tokens are JWTs. Pull the identity claims from the payload so signed-in
 * accounts show a human-readable name. Falls back gracefully when a claim is absent.
 */
internal fun parseGrokIdentity(token: String, json: Json): GrokIdentity {
    val claims = runCatching {
        val parts = token.split('.')
        require(parts.size == 3) { "Invalid JWT" }
        val payload = Base64.getUrlDecoder().decode(parts[1])
        json.parseToJsonElement(payload.decodeToString()).jsonObject
    }.getOrNull()

    fun claim(key: String): String? = claims?.get(key)?.jsonPrimitive?.contentOrNull

    val email = claim("email").orEmpty()
    val userId = claim("sub") ?: claim("user_id") ?: email
    val name = claim("name")
        ?: claim("preferred_username")
        ?: claim("given_name")
        ?: email.substringBefore('@').ifBlank { "Grok" }
    return GrokIdentity(userId = userId, email = email, name = name)
}

/**
 * Parse the shared-pool billing snapshot from `GET /v1/billing?format=credits`. This is the same
 * proto-JSON shape the Grok CLI consumes; zero-valued fields are omitted (an absent
 * `creditUsagePercent` genuinely means 0). The weekly window is only surfaced when the account's
 * current period is weekly — a legacy monthly-only account has no weekly pool.
 */
internal fun parseGrokCreditsUsage(root: JsonObject): GrokUsageSnapshot {
    val config = root["config"]?.jsonObject
    val period = config?.get("currentPeriod")?.jsonObject
    val periodType = period?.get("type")?.jsonPrimitive?.contentOrNull
    val usedPercent = config?.get("creditUsagePercent")?.jsonPrimitive?.doubleOrNull ?: 0.0
    val onDemandCap = config?.get("onDemandCap")?.jsonObject
        ?.get("val")?.jsonPrimitive?.doubleOrNull ?: 0.0

    val weekly = if (periodType?.uppercase()?.contains("WEEK") == true) {
        val start = period["start"]?.jsonPrimitive?.contentOrNull?.let(::parseIsoEpochSeconds)
        val end = period["end"]?.jsonPrimitive?.contentOrNull?.let(::parseIsoEpochSeconds)
        GrokUsageWindow(
            usedPercent = usedPercent,
            resetsAt = end,
            periodDurationMs = if (start != null && end != null) (end - start) * 1000 else null,
        )
    } else {
        null
    }
    return GrokUsageSnapshot(weekly = weekly, onDemandCap = onDemandCap)
}

/** The subscription tier name from `GET /v1/settings` (e.g. "SuperGrok"), or null if absent. */
internal fun parseGrokPlanName(root: JsonObject): String? =
    root["subscription_tier_display"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null }

private fun parseIsoEpochSeconds(raw: String): Long? =
    runCatching { OffsetDateTime.parse(raw.trim()).toEpochSecond() }.getOrNull()

/** Parse the weekly shared-pool quota returned by `/v1/billing?format=credits`. */
internal fun parseGrokWeeklyQuota(
    root: JsonObject,
    now: Instant = Clock.System.now(),
): ProviderQuota.UsageLimits? {
    val config = root["config"] as? JsonObject ?: return null
    val period = config["currentPeriod"] as? JsonObject ?: return null
    val type = (period["type"] as? JsonPrimitive)?.contentOrNull
    val start = period["start"]?.jsonPrimitive?.contentOrNull?.let(::parseIsoEpochSeconds)
    val end = period["end"]?.jsonPrimitive?.contentOrNull?.let(::parseIsoEpochSeconds)
    if (type?.uppercase()?.contains("WEEK") != true || start == null || end == null || end <= start) {
        return null
    }
    val percent = config["creditUsagePercent"]?.number()
        ?: if (Instant.fromEpochSeconds(end) > now) 0.0 else return null
    return ProviderQuota.UsageLimits(
        windows = listOf(
            UsageWindow(
                usedPercent = percent.coerceIn(0.0, 100.0),
                windowMinutes = ((end - start) / 60).toInt(),
                resetsAt = Instant.fromEpochSeconds(end),
            )
        ),
        limitReached = percent >= 100.0,
    )
}

/** Parse the monthly unified-billing quota returned by `/v1/billing`. */
internal fun parseGrokMonthlyQuota(root: JsonObject): ProviderQuota.UsageLimits? {
    val config = root["config"] as? JsonObject ?: return null
    val start = config["billingPeriodStart"]?.jsonPrimitive?.contentOrNull?.let(::parseIsoEpochSeconds)
    val end = config["billingPeriodEnd"]?.jsonPrimitive?.contentOrNull?.let(::parseIsoEpochSeconds)
    val limit = (config["monthlyLimit"] as? JsonObject)?.get("val")?.number()
    val used = (config["used"] as? JsonObject)?.get("val")?.number()
    if (start == null || end == null || end <= start || limit == null || limit <= 0.0 || used == null) {
        return null
    }
    return ProviderQuota.UsageLimits(
        windows = listOf(
            UsageWindow(
                usedPercent = (used / limit * 100.0).coerceIn(0.0, 100.0),
                windowMinutes = ((end - start) / 60).toInt(),
                resetsAt = Instant.fromEpochSeconds(end),
            )
        ),
        limitReached = used >= limit,
    )
}

internal fun JsonObject.isGrokUnifiedBilling(): Boolean =
    (this["config"] as? JsonObject)
        ?.get("isUnifiedBillingUser")
        ?.jsonPrimitive?.booleanOrNull == true

private fun JsonElement.number(): Double? =
    (this as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
