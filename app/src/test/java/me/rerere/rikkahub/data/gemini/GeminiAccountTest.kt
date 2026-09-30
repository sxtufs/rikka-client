package me.rerere.rikkahub.data.gemini

import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiAccountTest {
    @Test
    fun `expired account remains eligible so repository can refresh it`() {
        val account = GeminiAccount(
            id = "account",
            name = "Google account",
            accessToken = "access",
            refreshToken = "refresh",
            expiresAt = 0L,
            tokenStatus = GeminiTokenStatus.EXPIRED,
        )

        assertTrue(account.isAvailable(nowMillis = 1_000L))
    }
}
