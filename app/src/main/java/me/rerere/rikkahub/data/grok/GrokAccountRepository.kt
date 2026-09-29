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
        val existing = state.accounts.firstOrNull {
            (it.userId.isNotBlank() && it.userId == identity.userId) ||
                (it.email.isNotBlank() && it.email == identity.email)
        }
        val account = GrokAccount(
            id = existing?.id ?: identity.userId.ifBlank { identity.email }.ifBlank { accessToken.take(16) },
            userId = identity.userId,
            name = identity.name,
            email = identity.email,
            accessToken = accessToken,
            refreshToken = token["refresh_token"]?.jsonPrimitive?.contentOrNull
                ?: existing?.refreshToken ?: error("Missing refresh token"),
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
            val account = state.accounts[index]
            updateState(state.copy(nextAccountIndex = (index + 1) % state.accounts.size))
            runCatching { return ensureFreshLocked(account) }
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
        ensureFreshLocked(account, force = true)
    }

    suspend fun refreshAll() { accounts.value.forEach { runCatching { refreshAccount(it.id) } } }

    private suspend fun ensureFreshLocked(account: GrokAccount, force: Boolean = false): GrokAccount {
        if (!force && account.expiresAt > System.currentTimeMillis() + 30_000L) return account
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
}
