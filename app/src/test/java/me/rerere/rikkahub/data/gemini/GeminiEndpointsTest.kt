package me.rerere.rikkahub.data.gemini

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class GeminiEndpointsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `classifies a Cloud Code Assist error object`() {
        val result = classifyGeminiError(
            statusCode = null,
            body = """{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"rate limited"}}""",
            json = json,
        )

        assertEquals(429, result.status)
    }

    @Test
    fun `falls back to HTTP status when the error body is malformed`() {
        val result = classifyGeminiError(
            statusCode = 503,
            body = "not-json",
            json = json,
        )

        assertEquals(503, result.status)
    }
}
