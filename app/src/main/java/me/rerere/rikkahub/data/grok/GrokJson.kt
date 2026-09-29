package me.rerere.rikkahub.data.grok

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.OffsetDateTime
import java.util.Base64

internal data class GrokIdentity(val userId: String, val email: String, val name: String)

internal fun parseGrokIdentity(token: String, json: Json): GrokIdentity {
    val claims = runCatching {
        val parts = token.split('.')
        require(parts.size == 3)
        json.parseToJsonElement(Base64.getUrlDecoder().decode(parts[1]).decodeToString()).jsonObject
    }.getOrNull()
    fun claim(key: String) = claims?.get(key)?.jsonPrimitive?.contentOrNull
    val email = claim("email").orEmpty()
    return GrokIdentity(
        userId = claim("sub") ?: claim("user_id") ?: email,
        email = email,
        name = claim("name") ?: claim("preferred_username") ?: email.substringBefore('@').ifBlank { "Grok" },
    )
}

internal fun parseGrokCreditsUsage(root: JsonObject): GrokUsageSnapshot {
    val config = root["config"]?.jsonObject
    val period = config?.get("currentPeriod")?.jsonObject
    val start = period?.get("start")?.jsonPrimitive?.contentOrNull?.let(::parseIsoEpochSeconds)
    val end = period?.get("end")?.jsonPrimitive?.contentOrNull?.let(::parseIsoEpochSeconds)
    val weekly = if (period?.get("type")?.jsonPrimitive?.contentOrNull == "USAGE_PERIOD_TYPE_WEEKLY") {
        GrokUsageWindow(
            usedPercent = config["creditUsagePercent"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            resetsAt = end,
            periodDurationMs = if (start != null && end != null) (end - start) * 1000 else null,
        )
    } else null
    val cap = config?.get("onDemandCap")?.jsonObject?.get("val")?.jsonPrimitive?.doubleOrNull ?: 0.0
    return GrokUsageSnapshot(weekly = weekly, onDemandCap = cap)
}

internal fun parseGrokPlanName(root: JsonObject): String? =
    root["subscription_tier_display"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null }

private fun parseIsoEpochSeconds(raw: String): Long? =
    runCatching { OffsetDateTime.parse(raw.trim()).toEpochSecond() }.getOrNull()
