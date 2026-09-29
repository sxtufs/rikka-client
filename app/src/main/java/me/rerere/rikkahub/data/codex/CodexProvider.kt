package me.rerere.rikkahub.data.codex

import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
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

class CodexProvider(
    private val client: OkHttpClient,
    private val repository: CodexAccountRepository,
    private val json: Json,
    private val scope: CoroutineScope,
) : Provider<ProviderSetting.Codex> {
    override suspend fun listModels(providerSetting: ProviderSetting.Codex): List<Model> =
        withContext(Dispatchers.IO) {
            val account = repository.acquireAccount()
            val response = client.newCall(
                Request.Builder()
                    .url("$CODEX_API_BASE/models?client_version=$CLIENT_VERSION")
                    .codexHeaders(account)
                    .get()
                    .build()
            ).await()
            if (!response.isSuccessful) {
                if (response.code == 401) repository.markInvalid(account.id)
                error("Failed to get Codex models: ${response.code} ${response.body.string()}")
            }
            val models = (json.parseToJsonElement(response.body.string()) as? JsonObject)
                ?.get("models") as? JsonArray ?: return@withContext emptyList()
            models.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                if (item["visibility"]?.jsonPrimitive?.contentOrNull != "list") return@mapNotNull null
                val slug = item["slug"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val modalities = item["input_modalities"]?.jsonArray
                    ?.mapNotNull { modality ->
                        when ((modality as? JsonPrimitive)?.contentOrNull) {
                            "text" -> Modality.TEXT
                            "image" -> Modality.IMAGE
                            else -> null
                        }
                    }?.ifEmpty { listOf(Modality.TEXT) }
                    ?: listOf(Modality.TEXT, Modality.IMAGE)
                Model(
                    modelId = slug,
                    displayName = item["display_name"]?.jsonPrimitive?.contentOrNull ?: slug,
                    inputModalities = modalities,
                    abilities = buildList {
                        add(ModelAbility.TOOL)
                        if (item["supported_reasoning_levels"]?.jsonArray?.isNotEmpty() == true ||
                            item["supports_reasoning_summaries"]?.jsonPrimitive?.booleanOrNull == true
                        ) add(ModelAbility.REASONING)
                    },
                )
            }
        }

    override suspend fun generateText(
        providerSetting: ProviderSetting.Codex,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult {
        val account = repository.acquireAccount()
        return responseApiFor(account).generateText(
            syntheticSetting(providerSetting, account),
            withDefaultInstructions(messages),
            withCodexParams(params, account, stream = false),
        )
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.Codex,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> {
        val account = repository.acquireAccount()
        return responseApiFor(account).streamText(
            syntheticSetting(providerSetting, account),
            withDefaultInstructions(messages),
            withCodexParams(params, account, stream = true),
        )
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem> = error("Image generation is not supported by Codex")

    private fun Request.Builder.codexHeaders(account: CodexAccount): Request.Builder =
        header("Authorization", "Bearer ${account.accessToken}")
            .header("ChatGPT-Account-Id", account.chatgptAccountId)
            .header("OpenAI-Beta", "responses=experimental")
            .header("originator", "codex_cli_rs")
            .header("User-Agent", CODEX_USER_AGENT)

    private fun syntheticSetting(setting: ProviderSetting.Codex, account: CodexAccount) =
        ProviderSetting.OpenAI(
            id = setting.id,
            enabled = setting.enabled,
            name = setting.name,
            models = setting.models,
            baseUrl = CODEX_API_BASE,
            apiKey = account.accessToken,
            useResponseApi = true,
        )

    private fun withDefaultInstructions(messages: List<UIMessage>): List<UIMessage> =
        if (messages.any { it.role == MessageRole.SYSTEM }) messages
        else listOf(UIMessage.system(DEFAULT_INSTRUCTIONS)) + messages

    private fun withCodexParams(
        params: TextGenerationParams,
        account: CodexAccount,
        stream: Boolean,
    ): TextGenerationParams {
        val effort = params.model.abilities
            .takeIf { it.contains(ModelAbility.REASONING) }
            ?.let { codexReasoningEffort(params.reasoningLevel) }
        return params.copy(
            customHeaders = params.customHeaders + buildList {
                add(CustomHeader("ChatGPT-Account-Id", account.chatgptAccountId))
                add(CustomHeader("OpenAI-Beta", "responses=experimental"))
                add(CustomHeader("originator", "codex_cli_rs"))
                add(CustomHeader("User-Agent", CODEX_USER_AGENT))
                if (stream) add(CustomHeader("Accept", "text/event-stream"))
            },
            customBody = params.customBody + listOfNotNull(
                effort?.let { value ->
                    CustomBody("reasoning", buildJsonObject {
                        put("effort", value)
                        put("summary", "auto")
                    })
                }
            ),
        )
    }

    private fun responseApiFor(account: CodexAccount): ResponseAPI {
        val accountAwareClient = client.newBuilder()
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                parseCodexUsage(response.headers)?.let { usage ->
                    scope.launch { repository.updateUsage(account.id, usage) }
                }
                if (response.code == 401) {
                    scope.launch { repository.markInvalid(account.id) }
                }
                response
            }
            .build()
        return ResponseAPI(accountAwareClient)
    }

    private companion object {
        const val CODEX_API_BASE = "${CodexAccountRepository.CODEX_BASE_URL}/codex"
        const val CLIENT_VERSION = "0.144.5"
        const val DEFAULT_INSTRUCTIONS = "You are a helpful assistant."
        val CODEX_USER_AGENT = "codex_cli_rs/$CLIENT_VERSION (Android ${Build.VERSION.RELEASE}; " +
            "${Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64"})"
    }
}

internal fun codexReasoningEffort(level: ReasoningLevel): String? = when (level) {
    ReasoningLevel.AUTO, ReasoningLevel.OFF -> null
    ReasoningLevel.LOW -> "low"
    ReasoningLevel.MEDIUM -> "medium"
    ReasoningLevel.HIGH -> "high"
    ReasoningLevel.XHIGH, ReasoningLevel.MAX -> "xhigh"
}
