package me.rerere.rikkahub.data.grok

import me.rerere.rikkahub.AppScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.common.http.await
import kotlinx.coroutines.flow.flow
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
            val response = client.newCall(
                Request.Builder().url("$API_BASE/models").grokHeaders(account).get().build()
            ).await()
            if (!response.isSuccessful) {
                if (response.code == 401) repository.markInvalid(account.id)
                error("Failed to get Grok models (HTTP ${response.code})")
            }
            val data = json.parseToJsonElement(response.body.string()).jsonObject["data"]?.jsonArray
                ?: return@withContext emptyList()
            data.mapNotNull { element ->
                val id = element.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                if (isGrokImageModel(id)) {
                    Model(
                        modelId = id,
                        displayName = id,
                        type = ModelType.IMAGE,
                        inputModalities = listOf(Modality.TEXT),
                        outputModalities = listOf(Modality.IMAGE),
                    )
                } else {
                    Model(
                        modelId = id,
                        displayName = id,
                        inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
                        abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING),
                    )
                }
            }
        }

    override suspend fun generateText(
        providerSetting: ProviderSetting.Grok,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult {
        val account = repository.acquireAccount()
        return responseApiFor(account).generateText(
            syntheticSetting(providerSetting, account), messages, withGrokParams(params)
        )
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.Grok,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> {
        val account = repository.acquireAccount()
        return responseApiFor(account).streamText(
            syntheticSetting(providerSetting, account), messages, withGrokParams(params)
        )
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem> = flow {
        val account = repository.acquireAccount()
        val body = buildJsonObject {
            put("model", params.model.modelId)
            put("prompt", params.prompt)
            put("aspect_ratio", grokImageAspectRatio(params.size))
            put("resolution", GROK_IMAGE_RESOLUTION)
            put("n", params.numOfImages.coerceIn(1, 10))
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
                error("Failed to generate image (HTTP ${response.code})")
            }
            parseGrokImageResponse(bodyStr)
        }
        items.forEach { emit(it) }
    }

    private fun syntheticSetting(setting: ProviderSetting.Grok, account: GrokAccount) =
        ProviderSetting.OpenAI(
            id = setting.id,
            enabled = setting.enabled,
            name = setting.name,
            models = setting.models,
            baseUrl = API_BASE,
            apiKey = account.accessToken,
            useResponseApi = true,
        )

    private fun withGrokParams(params: TextGenerationParams): TextGenerationParams {
        val effort = if (params.model.abilities.contains(ModelAbility.REASONING)) {
            grokReasoningEffort(params.reasoningLevel)
        } else null
        // The Bearer token is applied by ResponseAPI from the synthetic OpenAI setting's
        // apiKey; only the User-Agent (and any reasoning body) are added here.
        return params.copy(
            customHeaders = params.customHeaders + CustomHeader("User-Agent", GrokOAuthManager.USER_AGENT),
            customBody = params.customBody + listOfNotNull(
                effort?.let { CustomBody("reasoning", buildJsonObject { put("effort", it) }) }
            ),
        )
    }

    private fun responseApiFor(account: GrokAccount): ResponseAPI =
        ResponseAPI(client.newBuilder().addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (response.code == 401) scope.launch { repository.markInvalid(account.id) }
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
        }.build())

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

    private fun Request.Builder.grokHeaders(account: GrokAccount): Request.Builder =
        header("Authorization", "Bearer ${account.accessToken}")
            .header("User-Agent", GrokOAuthManager.USER_AGENT)

    private companion object {
        const val API_BASE = "https://api.x.ai/v1"
        const val GROK_IMAGE_RESOLUTION = "1k"
    }
}

internal fun isGrokImageModel(id: String): Boolean = id.contains("image", ignoreCase = true)

internal fun grokImageAspectRatio(size: String): String = when (size) {
    "auto" -> "3:4"
    "1024x1024", "512x512", "256x256" -> "1:1"
    "1536x1024", "1792x1024" -> "3:2"
    "1024x1536", "1024x1792" -> "2:3"
    else -> "3:4"
}

internal fun grokReasoningEffort(level: ReasoningLevel): String? = when (level) {
    ReasoningLevel.AUTO, ReasoningLevel.OFF -> null
    ReasoningLevel.LOW -> "low"
    ReasoningLevel.MEDIUM -> "medium"
    ReasoningLevel.HIGH, ReasoningLevel.XHIGH, ReasoningLevel.MAX -> "high"
}
