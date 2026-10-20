package me.rerere.ai.provider

import androidx.compose.runtime.Composable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.reflect.KClass
import kotlin.uuid.Uuid

/** Legacy credential model retained only so pre-removal settings can still deserialize. */
@Serializable
data class OAuthCredential(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAt: Long = 0L,
    val accountId: String? = null,
    val email: String? = null,
    val plan: String? = null,
) {
    override fun toString(): String =
        "OAuthCredential(accessToken=${accessToken.masked()}, refreshToken=${refreshToken?.masked()}, " +
            "expiresAt=$expiresAt, accountId=$accountId, email=$email, plan=$plan)"

    private fun String.masked(): String = if (length <= 8) "***" else "${take(4)}***${takeLast(4)}"
}

@Serializable
enum class ClaudePromptCacheTtl(val apiValue: String?) {
    @SerialName("5m")
    FIVE_MINUTES(null),

    @SerialName("1h")
    ONE_HOUR("1h")
}

/**
 * 是否支持模型内置联网搜索（[BuiltInTools.Search]）。
 *
 * OpenAI 兼容提供商只有 Responses API 有 `web_search` 工具，Chat Completions 没有。
 */
val ProviderSetting.supportsBuiltInSearch: Boolean
    get() = when (this) {
        is ProviderSetting.OpenAI -> useResponseApi
        is ProviderSetting.Google -> true
        is ProviderSetting.Claude -> true
        // The custom OAuth transports do not expose the upstream built-in tool protocol.
        is ProviderSetting.Codex,
        is ProviderSetting.Grok,
        is ProviderSetting.GeminiOAuth -> false
        else -> false
    }

@Serializable
sealed class ProviderSetting {
    abstract val id: Uuid
    abstract val enabled: Boolean
    abstract val name: String
    abstract val models: List<Model>
    abstract val customHeaders: List<CustomHeader>

    abstract val builtIn: Boolean
    abstract val description:
        @Composable()
        () -> Unit
    abstract val shortDescription:
        @Composable()
        () -> Unit

    fun addModel(model: Model): ProviderSetting = copyProvider(models = models + model)

    fun editModel(model: Model): ProviderSetting =
        copyProvider(models = models.map { if (it.id == model.id) model.copy() else it })

    fun delModel(model: Model): ProviderSetting = copyProvider(models = models.filter { it.id != model.id })

    fun moveMove(from: Int, to: Int): ProviderSetting =
        copyProvider(models = models.toMutableList().apply { add(to, removeAt(from)) })

    abstract fun copyProvider(
        id: Uuid = this.id,
        enabled: Boolean = this.enabled,
        name: String = this.name,
        models: List<Model> = this.models,
        customHeaders: List<CustomHeader> = this.customHeaders,
        builtIn: Boolean = this.builtIn,
        description: @Composable (() -> Unit) = this.description,
        shortDescription: @Composable (() -> Unit) = this.shortDescription,
    ): ProviderSetting

    @Serializable
    @SerialName("openai")
    data class OpenAI(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = true,
        override var name: String = "OpenAI",
        override var models: List<Model> = emptyList(),
        override val quotaQuery: QuotaQuery = QuotaQuery(),
        override val customHeaders: List<CustomHeader> = emptyList(),
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        override var apiKey: String = "",
        override var baseUrl: String = "https://api.openai.com/v1",
        var chatCompletionsPath: String = "/chat/completions",
        var useResponseApi: Boolean = false,
        var includeHistoryReasoning: Boolean = true,
        var responsesPath: String = "/responses",
    ) : ApiKey() {
        override fun withQuotaQuery(query: QuotaQuery): ApiKey = copy(quotaQuery = query)

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            customHeaders: List<CustomHeader>,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return this.copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                customHeaders = customHeaders,
                builtIn = builtIn,
                description = description,
                shortDescription = shortDescription
            )
        }
    }

    @Serializable
    @SerialName("google")
    data class Google(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = true,
        override var name: String = "Google",
        override var models: List<Model> = emptyList(),
        override val quotaQuery: QuotaQuery =
            QuotaQuery(headers = listOf(CustomHeader("x-goog-api-key", "{{apiKey}}"))),
        override val customHeaders: List<CustomHeader> = emptyList(),
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        override var apiKey: String = "",
        override var baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
        var vertexAI: Boolean = false,
        var useServiceAccount: Boolean = false,
        var privateKey: String = "", // only for vertex AI service account
        var serviceAccountEmail: String = "", // only for vertex AI service account
        var location: String = "us-central1", // only for vertex AI service account
        var projectId: String = "", // only for vertex AI service account
        var useInteractionsApi: Boolean = false, // ignored when vertex AI is enabled
    ) : ApiKey() {
        override fun withQuotaQuery(query: QuotaQuery): ApiKey = copy(quotaQuery = query)

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            customHeaders: List<CustomHeader>,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return this.copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                customHeaders = customHeaders,
                builtIn = builtIn,
                description = description,
                shortDescription = shortDescription,
            )
        }
    }

    @Serializable
    @SerialName("claude")
    data class Claude(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = true,
        override var name: String = "Claude",
        override var models: List<Model> = emptyList(),
        override val quotaQuery: QuotaQuery = QuotaQuery(headers = listOf(CustomHeader("x-api-key", "{{apiKey}}"))),
        override val customHeaders: List<CustomHeader> = emptyList(),
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        override var apiKey: String = "",
        override var baseUrl: String = "https://api.anthropic.com/v1",
        var promptCaching: Boolean = false,
        var promptCacheTtl: ClaudePromptCacheTtl = ClaudePromptCacheTtl.FIVE_MINUTES,
    ) : ApiKey() {
        override fun withQuotaQuery(query: QuotaQuery): ApiKey = copy(quotaQuery = query)

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            customHeaders: List<CustomHeader>,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return this.copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                customHeaders = customHeaders,
                builtIn = builtIn,
                description = description,
                shortDescription = shortDescription,
            )
        }
    }

    /** 用 API Key 鉴权、地址可以自填的提供商 */
    @Serializable
    sealed class ApiKey : ProviderSetting() {
        abstract val apiKey: String
        abstract val baseUrl: String

        /** 用户自定义的额度查询 */
        abstract val quotaQuery: QuotaQuery

        abstract fun withQuotaQuery(query: QuotaQuery): ApiKey
    }

    /** Legacy account provider model retained for settings deserialization only. */
    @Serializable
    sealed class Account : ProviderSetting() {
        abstract val credential: OAuthCredential?
        abstract fun withCredential(credential: OAuthCredential?): Account
    }

    /**
     * Legacy ChatGPT provider. It is no longer registered or shown in the UI; this type remains
     * solely to decode old settings without deleting or corrupting user data.
     */
    @Deprecated("ChatGPT provider removed; use Codex")
    @Serializable
    @SerialName("chatgpt")
    data class ChatGPT(
        override val id: Uuid = Uuid.random(),
        override val enabled: Boolean = true,
        override val name: String = "ChatGPT",
        override val models: List<Model> = emptyList(),
        override val customHeaders: List<CustomHeader> = emptyList(),
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        override val credential: OAuthCredential? = null,
    ) : Account() {
        override fun withCredential(credential: OAuthCredential?): Account = copy(credential = credential)

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            customHeaders: List<CustomHeader>,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting = copy(
            id = id,
            enabled = enabled,
            name = name,
            models = models,
            customHeaders = customHeaders,
            builtIn = builtIn,
            description = description,
            shortDescription = shortDescription,
        )
    }

    @Serializable
    @SerialName("codex")
    data class Codex(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = false,
        override var name: String = "Codex",
        override var models: List<Model> = emptyList(),
        override val customHeaders: List<CustomHeader> = emptyList(),
        @Transient override val builtIn: Boolean = true,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
    ) : ProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            customHeaders: List<CustomHeader>,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting = copy(
            id = id,
            enabled = enabled,
            name = name,
            models = models,
            customHeaders = customHeaders,
            builtIn = builtIn,
            description = description,
            shortDescription = shortDescription,
        )
    }

    @Serializable
    @SerialName("grok")
    data class Grok(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = false,
        override var name: String = "Grok",
        override var models: List<Model> = emptyList(),
        override val customHeaders: List<CustomHeader> = emptyList(),
        @Transient override val builtIn: Boolean = true,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
    ) : ProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            customHeaders: List<CustomHeader>,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting = copy(
            id = id,
            enabled = enabled,
            name = name,
            models = models,
            customHeaders = customHeaders,
            builtIn = builtIn,
            description = description,
            shortDescription = shortDescription,
        )
    }

    @Serializable
    @SerialName("gemini_oauth")
    data class GeminiOAuth(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = false,
        override var name: String = "Gemini OAuth",
        override var models: List<Model> = emptyList(),
        override val customHeaders: List<CustomHeader> = emptyList(),
        @Transient override val builtIn: Boolean = true,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
    ) : ProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            customHeaders: List<CustomHeader>,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting = copy(
            id = id,
            enabled = enabled,
            name = name,
            models = models,
            customHeaders = customHeaders,
            builtIn = builtIn,
            description = description,
            shortDescription = shortDescription,
        )
    }

    companion object {
        val Types: List<KClass<out ProviderSetting>> by lazy {
            listOf(
                OpenAI::class,
                Google::class,
                Claude::class,
            )
        }
    }
}
