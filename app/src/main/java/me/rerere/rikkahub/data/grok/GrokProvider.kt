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
import okhttp3.OkHttpClient
import okhttp3.Request

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
                error("Failed to get Grok models: ${response.code} ${response.body.string()}")
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
    ): Flow<ImageGenerationItem> = error("Grok image generation is not enabled in the first pass")

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
            response
        }.build())

    private fun Request.Builder.grokHeaders(account: GrokAccount): Request.Builder =
        header("Authorization", "Bearer ${account.accessToken}")
            .header("User-Agent", GrokOAuthManager.USER_AGENT)

    private companion object { const val API_BASE = "https://api.x.ai/v1" }
}

internal fun isGrokImageModel(id: String): Boolean = id.contains("image", ignoreCase = true)

internal fun grokReasoningEffort(level: ReasoningLevel): String? = when (level) {
    ReasoningLevel.AUTO, ReasoningLevel.OFF -> null
    ReasoningLevel.LOW -> "low"
    ReasoningLevel.MEDIUM -> "medium"
    ReasoningLevel.HIGH, ReasoningLevel.XHIGH, ReasoningLevel.MAX -> "high"
}
