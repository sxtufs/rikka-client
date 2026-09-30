package me.rerere.rikkahub.data.gemini

import android.content.Context
import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.AppScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.oauth.CustomTabsOAuthAuthorizationLauncher
import me.rerere.oauth.OAuthAuthorizationLauncher
import me.rerere.oauth.OAuthHttpClient
import me.rerere.oauth.OAuthHttpClient.AuthorizationCodeTokenRequest
import me.rerere.oauth.OAuthHttpClient.AuthorizationRequest
import me.rerere.oauth.OAuthLoopbackCallbackServer
import okhttp3.OkHttpClient
import kotlin.time.Duration.Companion.minutes

/**
 * Loopback OAuth against Google's installed-app client (Antigravity).
 *
 * Google issues that client as a desktop app rather than a public one, so:
 * 1. The token exchange includes a client_secret (not just pkce_verifier)
 * 2. access_type=offline + prompt=consent are needed to get refresh tokens
 * 3. The redirect URI must use one of Google's registered loopback ports
 */
class GeminiOAuthManager(
    private val context: Context,
    private val scope: AppScope,
    private val client: OkHttpClient,
    private val repository: GeminiAccountRepository,
    private val authorizationLauncher: OAuthAuthorizationLauncher = CustomTabsOAuthAuthorizationLauncher,
) {
    private val _status = MutableStateFlow<GeminiOAuthStatus>(GeminiOAuthStatus.Idle)
    val status: StateFlow<GeminiOAuthStatus> = _status.asStateFlow()
    private var loginJob: Job? = null

    fun startLogin() {
        loginJob?.cancel()
        loginJob = scope.launch {
            val oauth = OAuthHttpClient(client)
            val state = oauth.generateState()
            val pkce = oauth.generatePkce()
            var server: OAuthLoopbackCallbackServer? = null
            var session: me.rerere.oauth.OAuthLoopbackCallbackSession? = null
            try {
                // Google's installed-app client has a registered loopback port
                val activeServer = OAuthLoopbackCallbackServer(
                    port = CALLBACK_PORT,
                    callbackPath = CALLBACK_PATH,
                    redirectHost = "localhost",
                )
                server = activeServer
                // The browser switch can background the app for several minutes. Tie the
                // callback session to the OAuth foreground service so the local server survives.
                val activeSession = activeServer.openSession(context, state)
                session = activeSession
                val redirectUri = activeSession.redirectUri

                val url = oauth.buildAuthorizationUrl(
                    AuthorizationRequest(
                        authorizationEndpoint = AUTHORIZE_URL,
                        clientId = CLIENT_ID,
                        redirectUri = redirectUri,
                        pkce = pkce,
                        state = state,
                        scope = SCOPES,
                        additionalParameters = mapOf(
                            "access_type" to "offline",
                            "prompt" to "consent",
                        ),
                    )
                )
                _status.value = GeminiOAuthStatus.Waiting
                authorizationLauncher.launch(context, url)

                val callback = activeSession.awaitCallback(10.minutes)
                    ?: error("Google sign-in timed out")
                if (callback.state != state) error("OAuth state mismatch")
                if (!callback.error.isNullOrBlank()) {
                    error(callback.errorDescription ?: callback.error ?: "Google authorization failed")
                }
                val code = callback.code ?: error("Missing authorization code")

                val token = oauth.exchangeAuthorizationCode(
                    AuthorizationCodeTokenRequest(
                        tokenEndpoint = TOKEN_URL,
                        clientId = CLIENT_ID,
                        clientSecret = CLIENT_SECRET,
                        code = code,
                        codeVerifier = pkce.verifier,
                        redirectUri = redirectUri,
                    )
                )

                val tokenJson = buildJsonObject {
                    put("access_token", token.accessToken)
                    token.refreshToken?.let { put("refresh_token", it) }
                    token.expiresIn?.let { put("expires_in", it) }
                    put("token_type", token.tokenType)
                }
                val account = repository.saveLogin(tokenJson.toString())
                _status.value = GeminiOAuthStatus.Success(account.id)
                runCatching { repository.refreshAccount(account.id) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _status.value = GeminiOAuthStatus.Error(error.message ?: "Gemini sign-in failed")
            } finally {
                session?.close()
                server = null
            }
        }
    }

    fun cancel() {
        loginJob?.cancel()
        loginJob = null
        _status.value = GeminiOAuthStatus.Idle
    }

    fun logout() {
        loginJob?.cancel()
        loginJob = null
        scope.launch {
            repository.accounts.value.toList().forEach { repository.delete(it.id) }
            _status.value = GeminiOAuthStatus.Idle
        }
    }

    fun consumeResult() {
        _status.value = GeminiOAuthStatus.Idle
    }

    companion object {
        // Google's published Antigravity installed-app credentials, in plaintext on purpose.
        // An installed-app OAuth client cannot hold a confidential secret: every copy of
        // Antigravity ships these and they are recoverable from any install, which is why Google
        // documents this client type as non-confidential. Encoding them would hide what they
        // are from a reader without hiding anything from anyone else.
        const val CLIENT_ID =
            "1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com"
        const val CLIENT_SECRET = "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"
        const val AUTHORIZE_URL = "https://accounts.google.com/o/oauth2/v2/auth"
        const val TOKEN_URL = "https://oauth2.googleapis.com/token"

        // cclog and experimentsandconfigs are Antigravity-specific and are part of what the
        // consent screen is registered for, so the grant is rejected without them.
        const val SCOPES = "https://www.googleapis.com/auth/cloud-platform " +
            "https://www.googleapis.com/auth/userinfo.email " +
            "https://www.googleapis.com/auth/userinfo.profile " +
            "https://www.googleapis.com/auth/cclog " +
            "https://www.googleapis.com/auth/experimentsandconfigs"

        // Antigravity registers a single fixed loopback port with the OAuth client
        private const val CALLBACK_PORT = 51121
        private const val CALLBACK_PATH = "/oauth-callback"
    }
}

sealed interface GeminiOAuthStatus {
    data object Idle : GeminiOAuthStatus
    data object Waiting : GeminiOAuthStatus
    data class Success(val accountId: String) : GeminiOAuthStatus
    data class Error(val message: String) : GeminiOAuthStatus
}
