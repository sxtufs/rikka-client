package me.rerere.rikkahub.data.gemini

import kotlinx.serialization.Serializable

@Serializable
data class GeminiAccount(
    val id: String,
    val userId: String = "",
    val name: String,
    val email: String = "",
    /**
     * The `cloudaicompanionProject` resolved once at sign-in. Every Code Assist generate request
     * must carry it, so it is stored with the tokens rather than rediscovered per request.
     * Optional for backward compatibility with accounts saved before this field existed; those
     * will re-resolve lazily.
     */
    val projectId: String? = null,
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val enabled: Boolean = true,
    val tokenStatus: GeminiTokenStatus = GeminiTokenStatus.UNKNOWN,
)

@Serializable
enum class GeminiTokenStatus { UNKNOWN, AVAILABLE, EXPIRED, INVALID }

internal fun GeminiAccount.isAvailable(nowMillis: Long = System.currentTimeMillis()): Boolean {
    if (!enabled || tokenStatus == GeminiTokenStatus.INVALID) return false
    return expiresAt > nowMillis
}