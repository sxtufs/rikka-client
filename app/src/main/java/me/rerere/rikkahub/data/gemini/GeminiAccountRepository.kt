package me.rerere.rikkahub.data.gemini

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.common.http.await
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class GeminiAccountRepository internal constructor(
    private val store: GeminiCredentialStore,
    private val client: OkHttpClient,
    private val json: Json,
) {
    private val mutex = Mutex()
    private var state = store.read().let { stored ->
        stored.copy(accounts = stored.accounts.map { account ->
            if (account.tokenStatus != GeminiTokenStatus.INVALID &&
                account.expiresAt <= System.currentTimeMillis()
            ) account.copy(tokenStatus = GeminiTokenStatus.EXPIRED) else account
        })
    }
    private val _accounts = MutableStateFlow(state.accounts)
    val accounts: StateFlow<List<GeminiAccount>> = _accounts.asStateFlow()

    /**
     * Persist a freshly exchanged token set. The sign-in identity and the Cloud Code Assist
     * project are each resolved here (outside the lock) because they are a network round trip
     * every time and holding the mutex across them would stall every concurrent generate
     * request behind a sign-in.
     */
    suspend fun saveLogin(tokenJson: String): GeminiAccount {
        val token = json.parseToJsonElement(tokenJson).jsonObject
        val accessToken = token["access_token"]?.jsonPrimitive?.contentOrNull
            ?: error("Missing access token")
        val identity = fetchIdentity(accessToken)
        val projectId = discoverProject(accessToken)
        val expiresAt = System.currentTimeMillis() +
            (token["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3600L) * 1000
        val refreshRequired = token["refresh_token"]?.jsonPrimitive?.contentOrNull
        return mutex.withLock {
            val id = identity.email.ifBlank { projectId }.ifBlank { accessToken.take(16) }
            val existing = state.accounts.firstOrNull { it.id == id }
            val account = GeminiAccount(
                id = id,
                userId = identity.sub,
                name = identity.name.ifBlank { identity.email.ifBlank { "Google account" } },
                email = identity.email,
                projectId = projectId,
                accessToken = accessToken,
                refreshToken = refreshRequired
                    ?: existing?.refreshToken
                    ?: error("Missing refresh token. Sign in again and grant offline access."),
                expiresAt = expiresAt,
                enabled = existing?.enabled ?: true,
                tokenStatus = GeminiTokenStatus.AVAILABLE,
            )
            updateState(state.copy(accounts = state.accounts.filterNot { it.id == account.id } + account))
            account
        }
    }

    suspend fun acquireAccount(): GeminiAccount = mutex.withLock {
        if (state.accounts.isEmpty()) error("No Gemini account is signed in")
        repeat(state.accounts.size) {
            val index = selectGeminiAccountIndex(state.accounts, state.nextAccountIndex)
                ?: error("No available Gemini account")
            val candidate = state.accounts[index]
            if (!candidate.isAvailable()) return@repeat
            updateState(state.copy(nextAccountIndex = (index + 1) % state.accounts.size))
            val fresh = runCatching { ensureFreshLocked(candidate) }.getOrNull() ?: return@repeat
            return fresh
        }
        error("No available Gemini account")
    }

    /** Resolve the project for accounts saved before projectId was persisted. */
    suspend fun ensureProject(account: GeminiAccount): GeminiAccount = mutex.withLock {
        if (!account.projectId.isNullOrBlank()) return@withLock account
        val projectId = discoverProject(account.accessToken)
        val updated = account.copy(projectId = projectId)
        replaceAccount(account.id) { updated }
        updated
    }

    suspend fun setEnabled(accountId: String, enabled: Boolean) = mutex.withLock {
        replaceAccount(accountId) { it.copy(enabled = enabled) }
    }

    suspend fun markInvalid(accountId: String) = mutex.withLock {
        replaceAccount(accountId) { it.copy(tokenStatus = GeminiTokenStatus.INVALID) }
    }

    suspend fun delete(accountId: String) = mutex.withLock {
        updateState(state.copy(accounts = state.accounts.filterNot { it.id == accountId }, nextAccountIndex = 0))
    }

    suspend fun refreshAccount(accountId: String): GeminiAccount = mutex.withLock {
        val account = state.accounts.firstOrNull { it.id == accountId }
            ?: error("Gemini account not found")
        ensureFreshLocked(account, force = true)
    }

    suspend fun refreshAll() {
        accounts.value.forEach { account -> runCatching { refreshAccount(account.id) } }
    }

    private suspend fun ensureFreshLocked(account: GeminiAccount, force: Boolean = false): GeminiAccount {
        if (!force && account.expiresAt > System.currentTimeMillis() + REFRESH_MARGIN_MS) return account
        val response = withContext(Dispatchers.IO) {
            val form = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("client_id", GeminiOAuthManager.CLIENT_ID)
                .add("client_secret", GeminiOAuthManager.CLIENT_SECRET)
                .add("refresh_token", account.refreshToken)
                .build()
            client.newCall(Request.Builder().url(GeminiOAuthManager.TOKEN_URL).post(form).build()).await()
        }
        val body = response.body.string()
        if (!response.isSuccessful) {
            if (response.code == 401) replaceAccount(account.id) { it.copy(tokenStatus = GeminiTokenStatus.INVALID) }
            error("Token refresh failed: ${response.code}")
        }
        val token = json.parseToJsonElement(body).jsonObject
        val updated = account.copy(
            accessToken = token["access_token"]?.jsonPrimitive?.contentOrNull
                ?: error("Missing refreshed access token"),
            refreshToken = token["refresh_token"]?.jsonPrimitive?.contentOrNull ?: account.refreshToken,
            expiresAt = System.currentTimeMillis() +
                (token["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3600L) * 1000,
            tokenStatus = GeminiTokenStatus.AVAILABLE,
        )
        replaceAccount(account.id) { updated }
        return updated
    }

    private fun selectGeminiAccountIndex(accounts: List<GeminiAccount>, startIndex: Int): Int? {
        if (accounts.isEmpty()) return null
        repeat(accounts.size) { offset ->
            val index = (startIndex + offset).mod(accounts.size)
            if (accounts[index].isAvailable()) return index
        }
        return null
    }

    /**
     * Resolves the signed-in user's identity from the OAuth userinfo endpoint. This is needed
     * because the loopback flow does not issue an `id_token`; the access token alone cannot be
     * decoded into a stable identity without a network round trip.
     */
    private suspend fun fetchIdentity(accessToken: String): GeminiIdentity = withContext(Dispatchers.IO) {
        client.newCall(
            Request.Builder()
                .url(USERINFO_URL)
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
        ).await().use { response ->
            if (!response.isSuccessful) {
                error("Google userinfo failed: ${response.code}")
            }
            val body = json.parseToJsonElement(response.body.string()).jsonObject
            GeminiIdentity(
                // OIDC userinfo returns `sub`; Google's legacy v1 endpoint returns `id`.
                sub = body["sub"]?.jsonPrimitive?.contentOrNull
                    ?: body["id"]?.jsonPrimitive?.contentOrNull
                    ?: error("Google userinfo did not return a stable user id"),
                email = body["email"]?.jsonPrimitive?.contentOrNull
                    ?: error("Google userinfo did not return email"),
                name = body["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }
    }

    /**
     * Resolve the `cloudaicompanionProject` this account generates against.
     *
     * Mirrors Antigravity's own onboarding: `loadCodeAssist` either hands back a project
     * outright or reports the tier to onboard against, and an account that has never used
     * Code Assist is provisioned one by `onboardUser`.
     */
    private suspend fun discoverProject(accessToken: String): String = withContext(Dispatchers.IO) {
        val loadResponse = client.newCall(
            Request.Builder()
                .url("$CODE_ASSIST_ENDPOINT/v1internal:loadCodeAssist")
                .antigravityHeaders(accessToken)
                .post(
                    json.encodeToString(buildJsonObject { put("metadata", clientMetadataJson()) })
                        .toRequestBody(JSON_MEDIA_TYPE)
                )
                .build()
        ).await()
        val loadBody = loadResponse.body.string()
        if (!loadResponse.isSuccessful) {
            error("loadCodeAssist failed (HTTP ${loadResponse.code})")
        }
        val load = json.parseToJsonElement(loadBody).jsonObject
        readProjectId(load["cloudaicompanionProject"])
            ?.let { return@withContext it }

        val tierId = selectGeminiTier(load)?.get("id")?.jsonPrimitive?.contentOrNull ?: TIER_LEGACY
        var operation: JsonObject? = null
        for (attempt in 0 until ONBOARD_MAX_ATTEMPTS) {
            if (attempt > 0) delay(ONBOARD_RETRY_INTERVAL_MS)
            val response = client.newCall(
                Request.Builder()
                    .url("$CODE_ASSIST_ENDPOINT/v1internal:onboardUser")
                    .antigravityHeaders(accessToken)
                    .post(
                        json.encodeToString(
                            buildJsonObject {
                                put("tierId", tierId)
                                put("metadata", clientMetadataJson())
                            }
                        ).toRequestBody(JSON_MEDIA_TYPE)
                    )
                    .build()
            ).await()
            val body = response.body.string()
            if (!response.isSuccessful) {
                error("onboardUser failed (HTTP ${response.code})")
            }
            val parsed = json.parseToJsonElement(body).jsonObject
            operation = parsed
            if (parsed["done"]?.jsonPrimitive?.contentOrNull == "true") break
        }
        val finished = operation ?: error("onboardUser returned nothing")
        readProjectId(finished["response"]?.jsonObject?.get("cloudaicompanionProject"))
            ?: error("onboardUser finished without returning a Cloud Code project")
    }

    private fun Request.Builder.antigravityHeaders(accessToken: String): Request.Builder =
        header("Authorization", "Bearer $accessToken")
            .header("User-Agent", buildAntigravityUserAgent())
            .header("Content-Type", "application/json")

    private fun readProjectId(element: kotlinx.serialization.json.JsonElement?): String? =
        ((element as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
            ?: (element as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull)
            ?.takeIf { it.isNotBlank() }

    private fun selectGeminiTier(load: JsonObject): JsonObject? =
        load["currentTier"]?.jsonObject
            ?: load["allowedTiers"]?.jsonArray
                ?.map { it.jsonObject }
                ?.firstOrNull { it["isDefault"]?.jsonPrimitive?.contentOrNull == "true" }

    private fun replaceAccount(accountId: String, transform: (GeminiAccount) -> GeminiAccount) {
        updateState(state.copy(accounts = state.accounts.map { if (it.id == accountId) transform(it) else it }))
    }

    private fun updateState(newState: GeminiAccountState) {
        state = newState
        store.write(newState)
        _accounts.value = newState.accounts
    }

    companion object {
        const val GEMINI_API_BASE = "https://generativelanguage.googleapis.com/v1beta"

        /**
         * The Cloud Code Assist production endpoint. The daily-tier fallbacks used by the
         * generate transport live in [me.rerere.rikkahub.data.gemini.GeminiProvider]; project
         * discovery (loadCodeAssist/onboardUser) intentionally stays on production only, where
         * sign-in already works.
         */
        const val CODE_ASSIST_ENDPOINT = "https://cloudcode-pa.googleapis.com"

        private const val USERINFO_URL = "https://www.googleapis.com/oauth2/v2/userinfo"
        private const val REFRESH_MARGIN_MS = 30_000L
        private const val TIER_LEGACY = "legacy-tier"
        private const val ONBOARD_RETRY_INTERVAL_MS = 2_000L
        private const val ONBOARD_MAX_ATTEMPTS = 5
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

private data class GeminiIdentity(
    val sub: String = "",
    val email: String = "",
    val name: String = "",
)
