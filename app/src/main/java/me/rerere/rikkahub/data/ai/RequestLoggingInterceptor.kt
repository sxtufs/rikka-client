package me.rerere.rikkahub.data.ai

import me.rerere.common.android.LogEntry
import me.rerere.common.android.Logging
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer

class RequestLoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!Logging.isRequestLoggingEnabled()) {
            return chain.proceed(chain.request())
        }

        val request = chain.request()
        val startTime = System.currentTimeMillis()

        val requestHeaders = request.headers.toRedactedMap()
        val requestBody = if (request.url.host in AUTH_HOSTS ||
            request.body?.contentType()?.toString()?.startsWith("application/x-www-form-urlencoded") == true
        ) {
            "[REDACTED]"
        } else {
            request.body?.let { body ->
                val buffer = Buffer()
                body.writeTo(buffer)
                buffer.readUtf8()
            }
        }

        val response: Response
        var error: String? = null

        try {
            response = chain.proceed(request)
        } catch (e: Exception) {
            error = e.message
            Logging.logRequest(
                LogEntry.RequestLog(
                    tag = "HTTP",
                    url = request.url.redacted().toString(),
                    method = request.method,
                    requestHeaders = requestHeaders,
                    requestBody = requestBody,
                    error = error
                )
            )
            throw e
        }

        val durationMs = System.currentTimeMillis() - startTime
        val responseHeaders = response.headers.toRedactedMap()

        Logging.logRequest(
            LogEntry.RequestLog(
                tag = "HTTP",
                url = request.url.redacted().toString(),
                method = request.method,
                requestHeaders = requestHeaders,
                requestBody = requestBody,
                responseCode = response.code,
                responseHeaders = responseHeaders,
                durationMs = durationMs,
                error = error
            )
        )

        return response
    }

    private fun okhttp3.Headers.toRedactedMap(): Map<String, String> =
        names().associateWith { name ->
            if (name.lowercase() in SENSITIVE_HEADERS) "[REDACTED]" else get(name).orEmpty()
        }

    private fun okhttp3.HttpUrl.redacted(): okhttp3.HttpUrl {
        val builder = newBuilder()
        queryParameterNames
            .filter { it.lowercase() in SENSITIVE_QUERY_PARAMETERS }
            .forEach { builder.setQueryParameter(it, "[REDACTED]") }
        return builder.build()
    }

    private companion object {
        val SENSITIVE_HEADERS = setOf(
            "authorization",
            "proxy-authorization",
            "cookie",
            "set-cookie",
            "x-api-key",
            "x-goog-api-key",
        )
        val SENSITIVE_QUERY_PARAMETERS = setOf(
            "key",
            "api_key",
            "access_token",
            "refresh_token",
            "code",
        )
        val AUTH_HOSTS = setOf(
            "auth.openai.com",
            "auth.x.ai",
            "oauth2.googleapis.com",
            "accounts.google.com",
        )
    }
}
