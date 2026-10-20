package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.QuotaQuery
import kotlin.uuid.Uuid

val DEFAULT_AUTO_MODEL_ID = Uuid.parse("b7055fb4-39f9-4042-a88a-0d80ed76cf08")
val DEFAULT_CODEX_PROVIDER_ID = Uuid.parse("7ce7e322-b995-4b0c-9d48-42e08dcfcdda")
val DEFAULT_GROK_PROVIDER_ID = Uuid.parse("8f3e1d20-4b6a-4c9e-a1f2-9d5c7e0b3a44")
val DEFAULT_GEMINI_OAUTH_PROVIDER_ID = Uuid.parse("2b6c1f84-73ad-4e35-b0c7-1a9e4d5f8c21")

val DEFAULT_PROVIDERS = listOf(
    ProviderSetting.OpenAI(
        id = Uuid.parse("1eeea727-9ee5-4cae-93e6-6fb01a4d051e"),
        name = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        apiKey = "",
        builtIn = true
    ),
    ProviderSetting.Codex(
        id = DEFAULT_CODEX_PROVIDER_ID,
        name = "Codex",
        enabled = false,
        builtIn = true,
    ),
    ProviderSetting.Grok(
        id = DEFAULT_GROK_PROVIDER_ID,
        name = "Grok",
        enabled = false,
        builtIn = true,
    ),
    ProviderSetting.GeminiOAuth(
        id = DEFAULT_GEMINI_OAUTH_PROVIDER_ID,
        name = "Gemini OAuth",
        enabled = false,
        builtIn = true,
    ),
    ProviderSetting.Google(
        id = Uuid.parse("6ab18148-c138-4394-a46f-1cd8c8ceaa6d"),
        name = "Gemini",
        apiKey = "",
        enabled = true,
        builtIn = true
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("f099ad5b-ef03-446d-8e78-7e36787f780b"),
        name = "DeepSeek",
        baseUrl = "https://api.deepseek.com/v1",
        apiKey = "",
        builtIn = true,
        quotaQuery = QuotaQuery(
            enabled = true,
            url = "{{baseUrl}}/user/balance",
            resultPath = "balance_infos[0].total_balance"
        )
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("d5734028-d39b-4d41-9841-fd648d65440e"),
        name = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1",
        apiKey = "",
        builtIn = true,
        quotaQuery = QuotaQuery(
            enabled = true,
            url = "{{baseUrl}}/credits",
            resultPath = "data.total_credits - data.total_usage",
        )
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("ff3cde7e-0f65-43d7-8fb2-6475c99f5990"),
        name = "xAI",
        baseUrl = "https://api.x.ai/v1",
        apiKey = "",
        enabled = false,
        builtIn = true,
        useResponseApi = true,
    ),
)
