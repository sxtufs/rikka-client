package me.rerere.rikkahub.data.grok

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.common.http.await
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest

class GrokAccountRepository internal constructor(
    private val store: GrokCredentialStore,
    private val client: OkHttpClient,
    private val json: Json,
) {
    private val mutex = Mutex()
    private var state = store.read().let { stored ->
        stored.copy(accounts = stored.accounts.map { account ->
            if (account.tokenStatus != GrokTokenStatus.INVALID && account.expiresAt <= System.currentTimeMillis()) {
                account.copy(tokenStatus = GrokTokenStatus.EXPIRED)
            } else account
        })
    }
    private val _accounts = MutableStateFlow(state.accounts)
    val accounts: StateFlow<List<GrokAccount>> = _accounts.asStateFlow()

    suspend fun saveLogin(tokenJson: String): GrokAccount = mutex.withLock {
        val token = json.parseToJsonElement(tokenJson).jsonObject
        val accessToken = token["access_token"]?.jsonPrimitive?.contentOrNull
            ?: error("Missing access token")
        val identity = parseGrokIdentity(
            token["id_token"]?.jsonPrimitive?.contentOrNull ?: accessToken,
            json,
        )
        val refreshToken = token["refresh_token"]?.jsonPrimitive?.contentOrNull
        val fallbackId = stableGrokAccountId(
            identity = identity,
            refreshToken = refreshToken,
            accessToken = accessToken,
        )
        val existing = state.accounts.firstOrNull {
            (identity.userId.isNotBlank() && it.userId == identity.userId) ||
                (identity.email.isNotBlank() && it.email == identity.email) ||
                it.id == fallbackId
        }
        val account = GrokAccount(
            id = existing?.id ?: fallbackId,
            userId = identity.userId,
            name = identity.name,
            email = identity.email,
            accessToken = accessToken,
            // xAI may omit refresh_token for CLI sessions. Keep an existing token when
            // available; otherwise the account remains usable until expiry and then asks the
            // user to sign in again instead of failing the initial login.
            refreshToken = refreshToken ?: existing?.refreshToken.orEmpty(),
            expiresAt = System.currentTimeMillis() +
                (token["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3600L) * 1000,
            enabled = existing?.enabled ?: true,
            tokenStatus = GrokTokenStatus.AVAILABLE,
            usage = existing?.usage,
        )
        updateState(state.copy(accounts = state.accounts.filterNot { it.id == account.id } + account))
        account
    }

    suspend fun acquireAccount(): GrokAccount = mutex.withLock {
        if (state.accounts.isEmpty()) error("No Grok account is signed in")
        repeat(state.accounts.size) {
            val index = selectGrokAccountIndex(state.accounts, state.nextAccountIndex)
                ?: error("No available Grok account")
            val candidate = state.accounts[index]
            if (!candidate.isAvailable()) return@repeat
            updateState(state.copy(nextAccountIndex = (index + 1) % state.accounts.size))
            val fresh = runCatching { ensureFreshLocked(candidate) }.getOrNull() ?: return@repeat
            return fresh
        }
        error("No available Grok account")
    }

    suspend fun setEnabled(accountId: String, enabled: Boolean) = mutex.withLock {
        replaceAccount(accountId) { it.copy(enabled = enabled) }
    }

    suspend fun markInvalid(accountId: String) = mutex.withLock {
        replaceAccount(accountId) { it.copy(tokenStatus = GrokTokenStatus.INVALID) }
    }

    suspend fun delete(accountId: String) = mutex.withLock {
        updateState(state.copy(accounts = state.accounts.filterNot { it.id == accountId }, nextAccountIndex = 0))
    }

    suspend fun refreshAccount(accountId: String): GrokAccount = mutex.withLock {
        val account = state.accounts.firstOrNull { it.id == accountId }
            ?: error("Grok account not found")
        // Some official CLI sessions do not expose a refresh token. They can still fetch
        // usage immediately after login; once the access token expires, acquireAccount() will
        // invalidate the session and the UI will ask for a new login.
        val fresh = if (account.refreshToken.isBlank()) {
            account
        } else {
            ensureFreshLocked(account, force = true)
        }
        // Usage/plan fetch is best-effort: never fail a token refresh just because billing is down.
        runCatching { fetchUsageLocked(fresh) }.getOrDefault(fresh)
    }

    suspend fun refreshAll() { accounts.value.forEach { runCatching { refreshAccount(it.id) } } }

    private suspend fun ensureFreshLocked(account: GrokAccount, force: Boolean = false): GrokAccount {
        if (!force && account.expiresAt > System.currentTimeMillis() + 30_000L) return account
        if (account.refreshToken.isBlank()) {
            replaceAccount(account.id) { it.copy(tokenStatus = GrokTokenStatus.INVALID) }
            error("Grok sign-in expired; sign in again")
        }
        val response = withContext(Dispatchers.IO) {
            val form = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("client_id", GrokOAuthManager.CLIENT_ID)
                .add("refresh_token", account.refreshToken)
                .build()
            client.newCall(Request.Builder().url(GrokOAuthManager.TOKEN_URL).post(form).build()).await()
        }
        val body = response.body.string()
        if (!response.isSuccessful) {
            if (response.code == 401) replaceAccount(account.id) { it.copy(tokenStatus = GrokTokenStatus.INVALID) }
            error("Token refresh failed: ${response.code}")
        }
        val token = json.parseToJsonElement(body).jsonObject
        val updated = account.copy(
            accessToken = token["access_token"]?.jsonPrimitive?.contentOrNull
                ?: error("Missing refreshed access token"),
            refreshToken = token["refresh_token"]?.jsonPrimitive?.contentOrNull ?: account.refreshToken,
            expiresAt = System.currentTimeMillis() +
                (token["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3600L) * 1000,
            tokenStatus = GrokTokenStatus.AVAILABLE,
        )
        replaceAccount(account.id) { updated }
        return updated
    }

    private suspend fun fetchUsageLocked(account: GrokAccount): GrokAccount {
        val credits = withContext(Dispatchers.IO) {
            client.newCall(
                Request.Builder().url(CREDITS_URL).grokBillingHeaders(account).get().build()
            ).await()
        }
        if (!credits.isSuccessful) {
            if (credits.code == 401) {
                replaceAccount(account.id) { it.copy(tokenStatus = GrokTokenStatus.INVALID) }
            }
            error("Failed to fetch Grok usage: ${credits.code}")
        }
        val snapshot = parseGrokCreditsUsage(
            json.parseToJsonElement(credits.body.string()).jsonObject
        )
        // Plan name is best-effort — never fail a usage refresh just because /settings is down.
        val planName = runCatching {
            val settings = withContext(Dispatchers.IO) {
                client.newCall(
                    Request.Builder().url(SETTINGS_URL).grokBillingHeaders(account).get().build()
                ).await()
            }
            if (settings.isSuccessful) {
                parseGrokPlanName(json.parseToJsonElement(settings.body.string()).jsonObject)
            } else {
                null
            }
        }.getOrNull()
        val updated = account.copy(
            tokenStatus = GrokTokenStatus.AVAILABLE,
            usage = snapshot.copy(planName = planName ?: account.usage?.planName),
        )
        replaceAccount(account.id) { updated }
        return updated
    }

    private fun Request.Builder.grokBillingHeaders(account: GrokAccount): Request.Builder =
        addHeader("Authorization", "Bearer ${account.accessToken}")
            .addHeader("X-XAI-Token-Auth", "xai-grok-cli")
            .addHeader("Accept", "application/json")

    private fun selectGrokAccountIndex(accounts: List<GrokAccount>, startIndex: Int): Int? {
        if (accounts.isEmpty()) return null
        repeat(accounts.size) { offset ->
            val index = (startIndex + offset).mod(accounts.size)
            if (accounts[index].isAvailable()) return index
        }
        return null
    }

    private fun replaceAccount(accountId: String, transform: (GrokAccount) -> GrokAccount) {
        updateState(state.copy(accounts = state.accounts.map { if (it.id == accountId) transform(it) else it }))
    }

    private fun updateState(newState: GrokAccountState) {
        state = newState
        store.write(newState)
        _accounts.value = newState.accounts
    }

    companion object {
        private const val REFRESH_MARGIN_MS = 30_000L
        // Grok subscription usage lives on the CLI billing proxy (the same surface the Grok CLI
        // uses), not on api.x.ai. The credits endpoint returns the shared weekly pool.
        private const val CREDITS_URL = "https://cli-chat-proxy.grok.com/v1/billing?format=credits"
        private const val SETTINGS_URL = "https://cli-chat-proxy.grok.com/v1/settings"
    }
}

internal fun stableGrokAccountId(
    identity: GrokIdentity,
    refreshToken: String?,
    accessToken: String,
): String {
    val claimedIdentity = identity.userId.ifBlank { identity.email }
    if (claimedIdentity.isNotBlank()) return claimedIdentity

    // Device-token responses may omit an id_token. Use a one-way stable key so signing in again
    // updates the same account without persisting a token in the account id.
    val seed = refreshToken ?: accessToken
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(seed.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return "grok-$digest"
}
