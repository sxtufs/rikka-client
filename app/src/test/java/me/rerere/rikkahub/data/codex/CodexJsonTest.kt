package me.rerere.rikkahub.data.codex

import java.util.Base64
import kotlinx.serialization.json.Json
import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CodexJsonTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `identity reads ChatGPT account claims from id token`() {
        val header = Base64.getUrlEncoder().withoutPadding().encodeToString("{}".encodeToByteArray())
        val claims = """
            {"sub":"user-1","email":"user@example.com","name":"User",
             "https://api.openai.com/auth":{"chatgpt_account_id":"acct-1","chatgpt_user_id":"user-1"}}
        """.trimIndent()
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(claims.encodeToByteArray())

        val identity = parseCodexIdentity("$header.$payload.signature", json)

        assertEquals("acct-1", identity.accountId)
        assertEquals("user-1", identity.userId)
        assertEquals("user@example.com", identity.email)
    }

    @Test
    fun `usage parser accepts Codex rate limit headers`() {
        val headers = Headers.headersOf(
            "x-codex-primary-used-percent", "42.5",
            "x-codex-primary-window-minutes", "300",
            "x-codex-primary-reset-after-seconds", "60",
        )

        val usage = parseCodexUsage(headers)

        assertNotNull(usage)
        assertEquals(42.5, requireNotNull(usage).primary?.usedPercent ?: 0.0, 0.0)
        assertEquals(300L, requireNotNull(usage).primary?.windowMinutes)
    }
}
