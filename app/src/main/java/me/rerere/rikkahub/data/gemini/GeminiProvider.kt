package me.rerere.rikkahub.data.gemini

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.providers.google.CODE_ASSIST_SAFETY_CATEGORIES
import me.rerere.ai.provider.providers.google.CodeAssistStreamDecoder
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.common.http.await
import me.rerere.rikkahub.AppScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import kotlin.coroutines.resume
import kotlin.uuid.Uuid

private const val TAG = "GeminiProvider"

/**
 * Talks to Google Cloud Code Assist with a signed-in Google account instead of an API key.
 *
 * The wire format is plain Gemini wrapped one level deep: the request body goes under `request`
 * next to the account's project and the model id, and each SSE payload carries the usual
 * candidates under `response`. That lets the whole message conversion be delegated to
 * [GoogleProvider] rather than duplicated here.
 */
class GeminiProvider(
    private val client: OkHttpClient,
    private val repository: GeminiAccountRepository,
    private val json: Json,
    private val scope: AppScope,
) : Provider<ProviderSetting.GeminiOAuth> {
    private val wire = GoogleProvider(client)

    override suspend fun listModels(providerSetting: ProviderSetting.GeminiOAuth): List<Model> =
        withContext(Dispatchers.IO) {
            val account = repository.acquireAccount()
            val outcome = client.postWithEndpointFallback(GEMINI_GENERATE_ENDPOINTS, json) { endpoint ->
                Request.Builder()
                    .url("$endpoint/v1internal:fetchAvailableModels")
                    .antigravityHeaders(account.accessToken)
                    .post("{}".toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            }
            if (!outcome.successful) {
                if (outcome.code == 401) scope.launch { repository.markInvalid(account.id) }
                error("Failed to list Gemini models: ${outcome.code} ${outcome.body}")
            }
            mapAvailableModels(outcome.body, json)
        }

    override suspend fun generateText(
        providerSetting: ProviderSetting.GeminiOAuth,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult {
        var collected = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()))
        val handler = StreamChunkHandler(params.model)
        streamText(providerSetting, messages, params).collect { chunk ->
            collected = handler.handle(collected, chunk)
        }
        val message = collected.last()
        return TextGenerationResult(
            id = "",
            model = params.model.modelId,
            message = message,
            usage = message.usage,
        )
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.GeminiOAuth,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = callbackFlow {
        val account = repository.acquireAccount()
        val project = account.projectId
            ?: error("Gemini OAuth account has no Code Assist project; sign in again")
        val requestBody = buildJsonObject {
            put("project", project)
            put("model", params.model.modelId)
            put(
                "request",
                wire.buildCompletionRequestBody(messages, params, CODE_ASSIST_SAFETY_CATEGORIES),
            )
        }
        val requestBodyText = json.encodeToString(requestBody)
        val responseId = Uuid.random().toString()
        val decoder = CodeAssistStreamDecoder(responseId, params.model.modelId)
        var lastFailure: Throwable? = null

        // Mirrors GEMINI_GENERATE_ENDPOINTS' fallback rule: every endpoint but the last gets one
        // attempt and hands off to the next on a transient failure; the last endpoint gets
        // GEMINI_MAX_RETRIES extra attempts with backoff. A retry only ever happens before any
        // chunk has been sent downstream, so a partial reply is never duplicated.
        endpointLoop@ for (index in GEMINI_GENERATE_ENDPOINTS.indices) {
            val endpoint = GEMINI_GENERATE_ENDPOINTS[index]
            val isLastEndpoint = index == GEMINI_GENERATE_ENDPOINTS.lastIndex
            val maxAttempts = if (isLastEndpoint) GEMINI_MAX_RETRIES + 1 else 1
            for (attempt in 0 until maxAttempts) {
                val request = Request.Builder()
                    .url("$endpoint/v1internal:streamGenerateContent?alt=sse")
                    .antigravityHeaders(account.accessToken)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "text/event-stream")
                    .post(requestBodyText.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                when (val outcome = runStreamAttempt(request, decoder, account)) {
                    is GeminiStreamAttemptOutcome.Success -> {
                        decoder.onClosed().forEach {
                            trySend(it).onFailure { e ->
                                Log.w(TAG, "onClosed: chunk dropped (${e?.message})")
                            }
                        }
                        close()
                        break@endpointLoop
                    }
                    is GeminiStreamAttemptOutcome.Failure -> {
                        lastFailure = outcome.cause
                        if (outcome.emitted || !outcome.classification.retryable) {
                            close(outcome.cause)
                            break@endpointLoop
                        }
                        if (attempt < maxAttempts - 1) {
                            delay(resolveGeminiRetryDelayMs(outcome.classification, attempt))
                        }
                        // Otherwise this endpoint is exhausted; fall through to the next one.
                    }
                }
            }
        }
        // Every branch above already closed the channel except the one where every endpoint and
        // every retry was exhausted without a single chunk ever going out; close() is idempotent,
        // so this is a no-op on every other path.
        close(lastFailure ?: IllegalStateException("Cloud Code Assist request failed"))
        // trySend silently drops a delta when the buffer is full, dropping characters mid-reply
        // (#1295), so the buffer must be unbounded - same as the other providers' streamText.
        awaitClose { }
    }.buffer(Channel.UNLIMITED)

    /**
     * Runs one `streamGenerateContent` attempt and reports how it ended. Whether to retry (a
     * different endpoint, another attempt on this one, or not at all) is the caller's call; this
     * function only needs to report whether anything was already sent downstream
     * ([GeminiStreamAttemptOutcome.Failure.emitted]), since that is what makes a retry unsafe.
     */
    private suspend fun ProducerScope<StreamChunk>.runStreamAttempt(
        request: Request,
        decoder: CodeAssistStreamDecoder,
        account: GeminiAccount,
    ): GeminiStreamAttemptOutcome = suspendCancellableCoroutine { cont ->
        var emitted = false

        fun resumeOnce(outcome: GeminiStreamAttemptOutcome) {
            if (cont.isActive) cont.resume(outcome)
        }

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                try {
                    val result = decoder.accept(SseEvent(id = id, event = type, data = data))
                    result.chunks.forEach { chunk ->
                        emitted = true
                        trySend(chunk).onFailure { e -> Log.w(TAG, "onEvent: chunk dropped (${e?.message})") }
                    }
                    if (result.completed) {
                        resumeOnce(GeminiStreamAttemptOutcome.Success)
                        eventSource.cancel()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "onEvent: failed to parse chunk", e)
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if (response?.code == 401) {
                    scope.launch { repository.markInvalid(account.id) }
                }
                // Read once: both the cause and the error classification want the body, and a
                // Response's body can only be consumed once.
                val bodyText = runCatching { response?.body?.string() }.getOrNull()
                val cause = t ?: IllegalStateException(
                    "Cloud Code Assist request failed: ${response?.code ?: "unknown"} ${bodyText.orEmpty()}")
                resumeOnce(
                    GeminiStreamAttemptOutcome.Failure(
                        cause = cause,
                        emitted = emitted,
                        classification = classifyGeminiError(response?.code, bodyText, json),
                    )
                )
            }

            override fun onClosed(eventSource: EventSource) {
                resumeOnce(GeminiStreamAttemptOutcome.Success)
            }
        }

        val eventSource = EventSources.createFactory(client).newEventSource(request, listener)
        cont.invokeOnCancellation { eventSource.cancel() }
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem> = error("Image generation is not supported by the Gemini OAuth provider")

    private fun Request.Builder.antigravityHeaders(accessToken: String): Request.Builder =
        header("Authorization", "Bearer $accessToken")
            .header("User-Agent", buildAntigravityUserAgent())

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

/** How one [GeminiProvider.runStreamAttempt] against a single endpoint ended. */
internal sealed interface GeminiStreamAttemptOutcome {
    data object Success : GeminiStreamAttemptOutcome
    data class Failure(
        val cause: Throwable,
        val emitted: Boolean,
        val classification: GeminiErrorClassification,
    ) : GeminiStreamAttemptOutcome
}

/**
 * Parses the `fetchAvailableModels` response body's `models` object into the list of [Model]s the
 * provider offers, filtering out internal-only entries. An absent or empty `models` object yields
 * an empty list.
 */
private fun mapAvailableModels(body: String, json: Json): List<Model> {
    val models = json.parseToJsonElement(body).jsonObject["models"]?.jsonObject
        ?: return emptyList()
    return models.values.mapNotNull { element ->
        val item = element.jsonObject
        val modelId = item["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        if (item["isInternal"]?.jsonPrimitive?.contentOrNull == "true") return@mapNotNull null
        Model(
            modelId = modelId,
            displayName = item["displayName"]?.jsonPrimitive?.contentOrNull ?: modelId,
            inputModalities = listOf(Modality.TEXT),
            abilities = buildList {
                add(ModelAbility.TOOL)
                if (item["supportsThinking"]?.jsonPrimitive?.contentOrNull == "true") {
                    add(ModelAbility.REASONING)
                }
            },
        )
    }
}
