package me.rerere.rikkahub.data.grok

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderQuota
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.ui.ImageAspectRatio
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.common.http.await
import me.rerere.rikkahub.AppScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.asResponseBody
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class GrokProvider(
    private val client: OkHttpClient,
    private val repository: GrokAccountRepository,
    private val json: Json,
    private val scope: AppScope,
) : Provider<ProviderSetting.Grok> {

    override suspend fun listModels(providerSetting: ProviderSetting.Grok): List<Model> =
        withContext(Dispatchers.IO) {
            val account = repository.acquireAccount()
            coroutineScope {
                val cli = async {
                    runCatching { fetchCatalog(account, GROK_CLI_MODELS_URL, cli = true) }
                }
                val api = async {
                    runCatching { fetchCatalog(account, "$API_BASE/models") }
                }
                val cliCatalog = cli.await()
                val apiCatalog = api.await()
                if (cliCatalog.isFailure && apiCatalog.isFailure) {
                    throw cliCatalog.exceptionOrNull() ?: apiCatalog.exceptionOrNull()!!
                }
                parseGrokModels(
                    cliCatalog = cliCatalog.getOrNull(),
                    apiCatalog = apiCatalog.getOrNull(),
                )
            }
        }

    override suspend fun getQuota(providerSetting: ProviderSetting.Grok): ProviderQuota? =
        repository.getQuota()

    private suspend fun fetchCatalog(
        account: GrokAccount,
        url: String,
        cli: Boolean = false,
    ): JsonObject {
        val response = client.newCall(
            Request.Builder()
                .url(url)
                .grokHeaders(account, cli = cli)
                .get()
                .build()
        ).await()
        val body = response.body.string()
        if (!response.isSuccessful) {
            // The CLI catalog is an optional enhancement; do not invalidate a usable account
            // merely because that auxiliary endpoint rejects the token.
            if (response.code == 401 && !cli) repository.markInvalid(account.id)
            error("Failed to get Grok models: ${response.code} $body")
        }
        return json.parseToJsonElement(body).jsonObject
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.Grok,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult {
        val account = repository.acquireAccount()
        return responseApiFor(account).generateText(
            providerSetting = syntheticSetting(providerSetting, account),
            messages = messages,
            params = withGrokParams(params, account),
        )
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.Grok,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> {
        val account = repository.acquireAccount()
        return responseApiFor(account).streamText(
            providerSetting = syntheticSetting(providerSetting, account),
            messages = messages,
            params = withGrokParams(params, account),
        )
    }

    // apiKey = the account's own OAuth token, so ResponseAPI's normal "Authorization: Bearer
    // <apiKey>" header lands on exactly the token grokHeaders used to set by hand.
    private fun syntheticSetting(providerSetting: ProviderSetting.Grok, account: GrokAccount) =
        ProviderSetting.OpenAI(
            id = providerSetting.id,
            enabled = providerSetting.enabled,
            name = providerSetting.name,
            models = providerSetting.models,
            baseUrl = API_BASE,
            apiKey = account.accessToken,
            useResponseApi = true,
        )

    private fun withGrokParams(params: TextGenerationParams, account: GrokAccount): TextGenerationParams {
        val reasoningEffort = params.model.abilities
            .takeIf { it.contains(ModelAbility.REASONING) }
            ?.let { grokReasoningEffort(params.reasoningLevel, params.model.modelId) }
        return params.copy(
            customHeaders = params.customHeaders + CustomHeader("User-Agent", GrokOAuthManager.USER_AGENT),
            customBody = params.customBody + listOfNotNull(
                reasoningEffort?.let { effort ->
                    CustomBody(
                        key = "reasoning",
                        value = buildJsonObject { put("effort", effort) },
                    )
                },
            ),
        )
    }

    /**
     * Wraps [client] with an account-scoped interceptor so a 401 (invalidated token) on this
     * account is detected from the same response that carries the model reply, without a second
     * network round-trip. Also patches a missing Content-Type so OkHttp's SSE factory recognizes
     * the stream - some xAI backend responses omit it.
     */
    private fun responseApiFor(account: GrokAccount): ResponseAPI {
        val accountAwareClient = client.newBuilder()
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (response.code == 401) {
                    scope.launch { repository.markInvalid(account.id) }
                }
                if (response.isSuccessful && response.header("Content-Type") == null) {
                    val body = response.body
                    response.newBuilder()
                        .header("Content-Type", "text/event-stream")
                        .body(
                            body.source().asResponseBody(
                                contentType = "text/event-stream".toMediaType(),
                                contentLength = body.contentLength(),
                            )
                        )
                        .build()
                } else {
                    response
                }
            }
            .build()
        return ResponseAPI(accountAwareClient)
    }

    // xAI's Grok Imagine image generation is OpenAI-compatible: a single POST to
    // /v1/images/generations, authenticated with the same subscription OAuth token used for chat.
    // Unlike OpenAI it takes aspect_ratio + resolution rather than a pixel size string.
    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem> = flow {
        val account = repository.acquireAccount()
        val body = buildJsonObject {
            put("model", params.model.modelId)
            put("prompt", params.prompt)
            put("aspect_ratio", grokImageAspectRatio(params.aspectRatio))
            put("resolution", GROK_IMAGE_RESOLUTION)
            put("n", params.numOfImages.coerceIn(1, 10))
            // Ask for base64 directly; grok-imagine's default URLs are short-lived (imgen.x.ai
            // temp URLs that 404 within minutes). parseGrokImageResponse still falls back to
            // downloading a url if a model ignores this.
            put("response_format", "b64_json")
        }
        val request = Request.Builder()
            .url("$API_BASE/images/generations")
            .grokHeaders(account)
            .addHeader("Content-Type", "application/json")
            .post(json.encodeToString(body).toRequestBody("application/json".toMediaType()))
            .build()
        val items = withContext(Dispatchers.IO) {
            val response = client.newCall(request).await()
            val bodyStr = response.body.string()
            if (!response.isSuccessful) {
                if (response.code == 401) repository.markInvalid(account.id)
                error("Failed to generate image: ${response.code} $bodyStr")
            }
            parseGrokImageResponse(bodyStr)
        }
        items.forEach { emit(it) }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun parseGrokImageResponse(bodyStr: String): List<ImageGenerationItem> {
        val data = json.parseToJsonElement(bodyStr).jsonObject["data"]?.jsonArray
            ?: error("No data in Grok image response")
        return data.map { element ->
            val obj = element.jsonObject
            val b64 = obj["b64_json"]?.jsonPrimitive?.contentOrNull
            if (b64 != null) {
                ImageGenerationItem(data = b64, mimeType = "image/png")
            } else {
                // grok-imagine returns short-lived imgen.x.ai URLs that 404 within minutes, so
                // materialise the bytes immediately rather than handing the URL downstream.
                val url = obj["url"]?.jsonPrimitive?.contentOrNull
                    ?: error("Grok image response had neither b64_json nor url")
                downloadImageAsBase64(url)
            }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun downloadImageAsBase64(url: String): ImageGenerationItem =
        withContext(Dispatchers.IO) {
            val response = client.newCall(Request.Builder().url(url).get().build()).await()
            if (!response.isSuccessful) {
                error("Failed to download generated image: ${response.code}")
            }
            val respBody = response.body
            val mimeType = respBody.contentType()?.toString() ?: "image/png"
            ImageGenerationItem(data = Base64.encode(respBody.bytes()), mimeType = mimeType)
        }

    private fun Request.Builder.grokHeaders(
        account: GrokAccount,
        cli: Boolean = false,
    ): Request.Builder {
        return header("Authorization", "Bearer ${account.accessToken}")
            .header("User-Agent", GrokOAuthManager.USER_AGENT)
            .apply {
                if (cli) header("X-XAI-Token-Auth", "xai-grok-cli")
            }
    }

    private companion object {
        const val API_BASE = "https://api.x.ai/v1"
        const val GROK_CLI_MODELS_URL = "https://cli-chat-proxy.grok.com/v1/models-v2"

        // Default output resolution for Grok Imagine image generation ("1k" or "2k").
        const val GROK_IMAGE_RESOLUTION = "1k"
    }
}

/**
 * Merge the CLI catalog first, then fill in models from xAI's public catalog.
 * The CLI catalog contains reasoning metadata and context windows; the public catalog is a
 * fallback when the CLI endpoint is unavailable. Imagine image models remain visible to the
 * custom image-generation implementation.
 */
internal fun parseGrokModels(
    cliCatalog: JsonObject?,
    apiCatalog: JsonObject?,
): List<Model> {
    val models = linkedMapOf<String, Model>()
    listOfNotNull(cliCatalog, apiCatalog).forEach { catalog ->
        (catalog["data"] as? JsonArray)
            .orEmpty()
            .filterIsInstance<JsonObject>()
            .forEach { row ->
                val meta = row["_meta"] as? JsonObject
                val id = row.string("model")
                    ?: row.string("modelId")
                    ?: row.string("id")
                    ?: meta?.string("model")
                    ?: meta?.string("modelId")
                    ?: return@forEach
                if (id in models) return@forEach
                if (row["hidden"]?.jsonPrimitive?.booleanOrNull == true ||
                    meta?.get("hidden")?.jsonPrimitive?.booleanOrNull == true
                ) return@forEach

                if (isGrokImageModel(id)) {
                    models[id] = Model(
                        modelId = id,
                        displayName = row.string("name") ?: row.string("displayName") ?: id,
                        type = ModelType.IMAGE,
                        inputModalities = listOf(Modality.TEXT),
                        outputModalities = listOf(Modality.IMAGE),
                    )
                    return@forEach
                }
                if (id.contains("video", ignoreCase = true) ||
                    id.contains("tts", ignoreCase = true) ||
                    id.contains("embed", ignoreCase = true)
                ) return@forEach

                val efforts = (row["reasoningEfforts"]
                    ?: row["reasoning_efforts"]
                    ?: meta?.get("reasoningEfforts")) as? JsonArray
                val contextWindow = listOf(
                    row["contextWindow"],
                    row["context_window"],
                    meta?.get("contextWindow"),
                    meta?.get("totalContextTokens"),
                ).firstNotNullOfOrNull { it?.jsonPrimitive?.contentOrNull?.toIntOrNull() }
                models[id] = Model(
                    modelId = id,
                    displayName = row.string("name")
                        ?: row.string("displayName")
                        ?: row.string("display_name")
                        ?: meta?.string("displayName")
                        ?: id,
                    inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
                    abilities = buildList {
                        add(ModelAbility.TOOL)
                        if (!efforts.isNullOrEmpty()) add(ModelAbility.REASONING)
                    },
                    contextLength = contextWindow ?: Model().contextLength,
                )
            }
    }
    return models.values.toList()
}

private fun JsonObject.string(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

/** Select the model-specific reasoning parameter accepted by xAI. */
internal fun grokReasoningEffort(level: ReasoningLevel, modelId: String): String? {
    if (Regex("""^grok-4\.20(-\d{4})?-(non-)?reasoning$""")
            .matches(modelId)
    ) return null
    return when (level) {
        ReasoningLevel.AUTO -> null
        ReasoningLevel.OFF -> "none"
        ReasoningLevel.LOW -> "low"
        ReasoningLevel.MEDIUM -> "medium"
        ReasoningLevel.HIGH -> "high"
        ReasoningLevel.XHIGH, ReasoningLevel.MAX ->
            if (modelId == "grok-4.6") "xhigh" else "high"
    }
}

// The Grok Imagine image-generation models are listed by /models under the "*image*" family
// (e.g. grok-imagine-image, grok-imagine-image-quality). "grok-imagine-video" has no "image"
// substring, so the video-generation models are naturally excluded.
internal fun isGrokImageModel(id: String): Boolean = id.contains("image", ignoreCase = true)

internal fun grokImageAspectRatio(ratio: ImageAspectRatio): String = when (ratio) {
    ImageAspectRatio.SQUARE -> "1:1"
    ImageAspectRatio.LANDSCAPE -> "16:9"
    ImageAspectRatio.PORTRAIT -> "9:16"
}

internal fun grokReasoningEffort(level: ReasoningLevel): String? {
    return when (level) {
        ReasoningLevel.AUTO -> null
        ReasoningLevel.OFF -> null
        ReasoningLevel.LOW -> "low"
        ReasoningLevel.MEDIUM -> "medium"
        ReasoningLevel.HIGH -> "high"
        ReasoningLevel.XHIGH -> "high"
        ReasoningLevel.MAX -> "high"
    }
}
