package me.rerere.rikkahub.data.gemini

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.common.http.await
import okhttp3.OkHttpClient
import okhttp3.Request

/** The outcome of one Cloud Code Assist HTTP call, with the body always already drained to text. */
internal data class GeminiHttpOutcome(val code: Int, val body: String) {
    val successful: Boolean get() = code in 200..299
}

/** How a Cloud Code Assist error response should steer the caller's retry loop. */
internal data class GeminiErrorClassification(
    val status: Int?,
    val reason: String?,
    val retryDelayMs: Long?,
    val retryable: Boolean,
)

// oh-my-pi MAX_RETRIES / BASE_DELAY_MS: the last endpoint in the fallback chain gets this many
// extra attempts, backing off exponentially from this base.
internal const val GEMINI_MAX_RETRIES = 3
internal const val GEMINI_RETRY_BASE_DELAY_MS = 1_000L
// The longest retry delay worth actually waiting out (also the threshold at which a
// RATE_LIMIT_EXCEEDED is really a long quota window rather than a short throttle).
internal const val GEMINI_RETRY_DELAY_CAP_MS = 5 * 60 * 1000L

// Cloud Code Assist reuses RATE_LIMIT_EXCEEDED for a per-model daily quota, distinguishable only
// by this phrase in error.message.
private val ANTIGRAVITY_MODEL_QUOTA_PATTERN =
    Regex("\bexhausted your capacity on this model\b", RegexOption.IGNORE_CASE)
private const val GOOGLE_RPC_ERROR_INFO_TYPE = "type.googleapis.com/google.rpc.ErrorInfo"
private const val GOOGLE_RPC_RETRY_INFO_TYPE = "type.googleapis.com/google.rpc.RetryInfo"
private val RETRY_DELAY_VALUE_PATTERN = Regex("""^([0-9.]+)(ms|s)$""")

/**
 * Classifies a Cloud Code Assist error response for the retry loop. [statusCode] wins when
 * present; when null (an error delivered inside a 200 SSE event body) this falls back to the
 * body's own numeric `error.code`. A missing or unparseable [body] degrades to classifying off
 * whatever status is available and never throws.
 */
internal fun classifyGeminiError(statusCode: Int?, body: String?, json: Json): GeminiErrorClassification {
    val error = runCatching {
        body?.let { rawBody ->
            (json.parseToJsonElement(rawBody) as? JsonObject)
                ?.get("error") as? JsonObject
        }
    }.getOrNull()
    val effectiveStatus = statusCode ?: error?.get("code")?.jsonPrimitive?.intOrNull
    val transientStatus = effectiveStatus == 408 || effectiveStatus == 429 ||
        (effectiveStatus != null && effectiveStatus >= 500)
    val rpcStatus = error?.get("status")?.jsonPrimitive?.contentOrNull
    val details = (error?.get("details") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    var reason = if (rpcStatus?.uppercase() == "RESOURCE_EXHAUSTED") {
        details.firstOrNull { it["@type"]?.jsonPrimitive?.contentOrNull == GOOGLE_RPC_ERROR_INFO_TYPE }
            ?.get("reason")?.jsonPrimitive?.contentOrNull
    } else {
        null
    }
    val message = error?.get("message")?.jsonPrimitive?.contentOrNull
    if (reason == "RATE_LIMIT_EXCEEDED" && message != null &&
        ANTIGRAVITY_MODEL_QUOTA_PATTERN.containsMatchIn(message)
    ) {
        reason = "QUOTA_EXHAUSTED"
    }
    val retryDelayMs = details
        .firstOrNull { it["@type"]?.jsonPrimitive?.contentOrNull == GOOGLE_RPC_RETRY_INFO_TYPE }
        ?.get("retryDelay")?.jsonPrimitive?.contentOrNull
        ?.let(::parseGeminiRetryDelayMs)
    val quotaExhausted = reason == "QUOTA_EXHAUSTED" ||
        (reason == "RATE_LIMIT_EXCEEDED" && retryDelayMs != null && retryDelayMs >= GEMINI_RETRY_DELAY_CAP_MS)
    val withinRetryBudget = retryDelayMs == null || retryDelayMs <= GEMINI_RETRY_DELAY_CAP_MS
    return GeminiErrorClassification(
        status = effectiveStatus,
        reason = reason ?: rpcStatus,
        retryDelayMs = retryDelayMs,
        retryable = transientStatus && !quotaExhausted && withinRetryBudget,
    )
}

/** Parses a `google.rpc.RetryInfo.retryDelay` value such as `"12s"` or `"500ms"` into milliseconds. */
internal fun parseGeminiRetryDelayMs(raw: String): Long? {
    val match = RETRY_DELAY_VALUE_PATTERN.find(raw.trim()) ?: return null
    val (numberPart, unit) = match.destructured
    val amount = numberPart.toDoubleOrNull() ?: return null
    return (if (unit == "ms") amount else amount * 1000.0).toLong()
}

/** The delay before the next retry attempt: the server's own hint if it gave one, else exponential backoff. */
internal fun resolveGeminiRetryDelayMs(classification: GeminiErrorClassification, attempt: Int): Long {
    val backoff = GEMINI_RETRY_BASE_DELAY_MS * (1L shl attempt)
    return (classification.retryDelayMs ?: backoff).coerceAtMost(GEMINI_RETRY_DELAY_CAP_MS)
}

/**
 * Issues a Cloud Code Assist POST across [endpoints] in order: every endpoint but the last gets
 * exactly one attempt and a transient failure moves on to the next immediately with no delay;
 * the last endpoint gets [GEMINI_MAX_RETRIES] extra attempts with backoff, honoring a
 * `google.rpc.RetryInfo` delay when [classifyGeminiError] finds one, capped at
 * [GEMINI_RETRY_DELAY_CAP_MS]. A non-transient status, or one classified as a long quota window,
 * stops the whole chain immediately rather than trying the remaining endpoints.
 */
internal suspend fun OkHttpClient.postWithEndpointFallback(
    endpoints: List<String>,
    json: Json,
    buildRequest: (endpoint: String) -> Request,
): GeminiHttpOutcome {
    var last: GeminiHttpOutcome? = null
    for (index in endpoints.indices) {
        val endpoint = endpoints[index]
        val isLastEndpoint = index == endpoints.lastIndex
        val maxAttempts = if (isLastEndpoint) GEMINI_MAX_RETRIES + 1 else 1
        for (attempt in 0 until maxAttempts) {
            val response = withContext(Dispatchers.IO) { newCall(buildRequest(endpoint)).await() }
            val outcome = GeminiHttpOutcome(response.code, response.body.string())
            last = outcome
            if (outcome.successful) return outcome
            val classification = classifyGeminiError(outcome.code, outcome.body, json)
            if (!classification.retryable) return outcome
            if (attempt < maxAttempts - 1) {
                delay(resolveGeminiRetryDelayMs(classification, attempt))
            }
        }
    }
    return last ?: error("No Cloud Code Assist endpoint was attempted")
}

// Antigravity generate traffic (streamGenerateContent, fetchAvailableModels) tries the daily
// Cloud Code Assist tier first, then its sandbox twin, falling back to prod as a last resort so a
// signed-in account still works if both daily tiers are unreachable. Project discovery
// (loadCodeAssist/onboardUser) stays on prod only; sign-in already works there.
internal const val ANTIGRAVITY_DAILY_ENDPOINT = "https://daily-cloudcode-pa.googleapis.com"
internal const val ANTIGRAVITY_DAILY_SANDBOX_ENDPOINT = "https://daily-cloudcode-pa.sandbox.googleapis.com"
internal val GEMINI_GENERATE_ENDPOINTS = listOf(
    ANTIGRAVITY_DAILY_ENDPOINT,
    ANTIGRAVITY_DAILY_SANDBOX_ENDPOINT,
    "https://cloudcode-pa.googleapis.com",
)

/**
 * Builds the pinned Antigravity `User-Agent` header value. The backend gates model routing on the
 * client it believes it is talking to, so os_type/arch are pinned to the darwin/arm64 reference
 * client independent of the host platform.
 */
internal fun buildAntigravityUserAgent(
    version: String = "2.8.0",
    os: String = "darwin",
    arch: String = "arm64",
    cl: String = "963137146",
): String = "antigravity/hub/$version (aidev_client; os_type=$os; arch=$arch; cl=$cl)"

/**
 * The Cloud Code Assist request-body `metadata` describing the Antigravity client. Unlike the
 * Gemini CLI, Antigravity sends this in the body rather than as a `Client-Metadata` header.
 */
internal fun clientMetadataJson(): JsonObject = buildJsonObject {
    put("ideType", "ANTIGRAVITY")
    put("platform", "PLATFORM_UNSPECIFIED")
    put("pluginType", "GEMINI")
}
