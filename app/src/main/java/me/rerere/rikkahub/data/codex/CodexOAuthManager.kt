package me.rerere.rikkahub.data.codex

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.oauth.CustomTabsOAuthAuthorizationLauncher
import me.rerere.oauth.OAuthHttpClient
import me.rerere.oauth.OAuthHttpClient.AuthorizationCodeTokenRequest
import me.rerere.oauth.OAuthHttpClient.AuthorizationRequest
import me.rerere.oauth.OAuthLoopbackCallbackServer
import okhttp3.OkHttpClient
import kotlin.time.Duration.Companion.minutes

class CodexOAuthManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val client: OkHttpClient,
    private val repository: CodexAccountRepository,
) {
    private val _status = MutableStateFlow<CodexOAuthStatus>(CodexOAuthStatus.Idle)
    val status: StateFlow<CodexOAuthStatus> = _status.asStateFlow()
    private var loginJob: Job? = null
    private var callbackServer: OAuthLoopbackCallbackServer? = null

    fun startLogin() {
        loginJob?.cancel()
        loginJob = scope.launch {
            val state = OAuthHttpClient(client).generateState()
            val oauth = OAuthHttpClient(client)
            val pkce = oauth.generatePkce()
            val server = OAuthLoopbackCallbackServer(port = 0, callbackPath = "/auth/callback")
            callbackServer = server
            var session: me.rerere.oauth.OAuthLoopbackCallbackSession? = null
            try {
                session = server.openSession(state)
                val url = oauth.buildAuthorizationUrl(
                    AuthorizationRequest(
                        authorizationEndpoint = AUTHORIZE_URL,
                        clientId = CLIENT_ID,
                        redirectUri = session.redirectUri,
                        pkce = pkce,
                        state = state,
                        scope = DEFAULT_SCOPES,
                        additionalParameters = mapOf(
                            "id_token_add_organizations" to "true",
                            "codex_cli_simplified_flow" to "true",
                            "originator" to "codex_cli_rs",
                        ),
                    )
                )
                _status.value = CodexOAuthStatus.Waiting
                CustomTabsOAuthAuthorizationLauncher.launch(context, url)
                val callback = session.awaitCallback(10.minutes)
                    ?: error("OpenAI sign-in timed out")
                if (callback.state != state) error("OAuth state mismatch")
                if (!callback.error.isNullOrBlank()) {
                    error(callback.errorDescription ?: callback.error ?: "OpenAI authorization failed")
                }
                val code = callback.code ?: error("Missing authorization code")
                val token = oauth.exchangeAuthorizationCode(
                    AuthorizationCodeTokenRequest(
                        tokenEndpoint = TOKEN_URL,
                        clientId = CLIENT_ID,
                        code = code,
                        codeVerifier = pkce.verifier,
                        redirectUri = session.redirectUri,
                    )
                )
                val tokenJson = buildJsonObject {
                    put("access_token", token.accessToken)
                    token.refreshToken?.let { put("refresh_token", it) }
                    token.expiresIn?.let { put("expires_in", it) }
                    put("token_type", token.tokenType)
                }
                val account = repository.saveLogin(tokenJson.toString())
                _status.value = CodexOAuthStatus.Success(account.id)
                runCatching { repository.refreshAccount(account.id) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _status.value = CodexOAuthStatus.Error(error.message ?: "Codex sign-in failed")
            } finally {
                session?.close()
                callbackServer = null
            }
        }
    }

    fun cancel() {
        loginJob?.cancel()
        loginJob = null
        _status.value = CodexOAuthStatus.Idle
    }

    fun consumeResult() {
        _status.value = CodexOAuthStatus.Idle
    }

    companion object {
        const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        const val AUTHORIZE_URL = "https://auth.openai.com/oauth/authorize"
        const val TOKEN_URL = "https://auth.openai.com/oauth/token"
        const val DEFAULT_SCOPES = "openid profile email offline_access"
        const val REFRESH_SCOPES = DEFAULT_SCOPES
    }
}

sealed interface CodexOAuthStatus {
    data object Idle : CodexOAuthStatus
    data object Waiting : CodexOAuthStatus
    data class Success(val accountId: String) : CodexOAuthStatus
    data class Error(val message: String) : CodexOAuthStatus
}
