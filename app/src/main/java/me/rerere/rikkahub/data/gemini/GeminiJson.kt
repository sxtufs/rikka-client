package me.rerere.rikkahub.data.gemini

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

internal data class GeminiIdentity(
    val userId: String,
    val email: String,
    val name: String,
    val picture: String?,
)

internal fun parseGeminiIdentity(token: String, json: Json): GeminiIdentity {
    val parts = token.split('.')
    require(parts.size == 3) { "Invalid ID token" }
    val payload = Base64.getUrlDecoder().decode(parts[1])
    val claims = json.parseToJsonElement(payload.decodeToString()).jsonObject
    
    return GeminiIdentity(
        userId = claims["sub"]?.jsonPrimitive?.contentOrNull ?: error("Missing sub claim"),
        email = claims["email"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        name = claims["name"]?.jsonPrimitive?.contentOrNull
            ?: claims["email"]?.jsonPrimitive?.contentOrNull
            ?: "Google User",
        picture = claims["picture"]?.jsonPrimitive?.contentOrNull,
    )
}