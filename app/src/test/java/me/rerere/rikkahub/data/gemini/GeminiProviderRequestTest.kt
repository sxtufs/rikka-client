package me.rerere.rikkahub.data.gemini

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.ui.UIMessage
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the CCA request post-processing helpers in [GeminiProvider]: Code Assist fronts
 * Anthropic models that reject a thinking budget under 1024, so `raiseThinkingBudgetToClaudeFloor`
 * must run - and be seen by `raiseMaxTokensAboveThinkingBudget` - before the request is sent. It
 * also fronts GPT-OSS models, which reject any `thinkingConfig` at all, so
 * `stripThinkingConfigForGptOss` must run before both of those. All three are file-private
 * top-level functions, so reflection targets the `GeminiProviderKt` facade class Kotlin generates
 * for a file's top-level declarations.
 */
class GeminiProviderRequestTest {

    private val geminiProviderKt = Class.forName("me.rerere.rikkahub.data.gemini.GeminiProviderKt")

    private fun invokeRaiseThinkingBudget(request: JsonObject): JsonObject {
        val method = geminiProviderKt.getDeclaredMethod(
            "raiseThinkingBudgetToClaudeFloor",
            JsonObject::class.java
        )
        method.isAccessible = true
        return method.invoke(null, request) as JsonObject
    }

    private fun invokeRaiseMaxTokens(request: JsonObject): JsonObject {
        val method = geminiProviderKt.getDeclaredMethod(
            "raiseMaxTokensAboveThinkingBudget",
            JsonObject::class.java
        )
        method.isAccessible = true
        return method.invoke(null, request) as JsonObject
    }

    private fun invokeStripThinkingConfigForGptOss(modelId: String, request: JsonObject): JsonObject {
        val method = geminiProviderKt.getDeclaredMethod(
            "stripThinkingConfigForGptOss",
            String::class.java,
            JsonObject::class.java
        )
        method.isAccessible = true
        return method.invoke(null, modelId, request) as JsonObject
    }

    private fun requestWithBudget(budget: Int): JsonObject = buildJsonObject {
        put("generationConfig", buildJsonObject {
            put("thinkingConfig", buildJsonObject {
                put("thinkingBudget", budget)
            })
        })
    }

    private fun budgetOf(request: JsonObject): Int? =
        request["generationConfig"]?.jsonObject
            ?.get("thinkingConfig")?.jsonObject
            ?.get("thinkingBudget")?.jsonPrimitive?.intOrNull

    private val googleProvider = GoogleProvider(OkHttpClient())

    @Test
    fun `Code Assist sanitizes every nested tool schema while preserving useful constraints`() {
        val body = googleProvider.buildCompletionRequestBody(
            messages = listOf(UIMessage.user("use the tools")),
            params = TextGenerationParams(
                model = Model(modelId = "claude-compatible", abilities = listOf(ModelAbility.TOOL)),
                tools = listOf(complexSchemaTool("first"), complexSchemaTool("second")),
            ),
            safetyCategories = emptyList(),
            sanitizeToolSchemas = true,
        )

        val declarations = body["tools"]!!.jsonArray.single()
            .jsonObject["functionDeclarations"]!!.jsonArray
        assertEquals(2, declarations.size)
        declarations.forEach { declaration ->
            val schema = declaration.jsonObject["parameters"]!!
            assertNoUnsupportedSchemaKeywords(schema)
            val payload = schema.jsonObject["properties"]!!.jsonObject["payload"]!!.jsonObject
            assertEquals("object", payload["type"]!!.jsonPrimitive.content)
            assertTrue(payload["required"]!!.jsonArray.map { it.jsonPrimitive.content }.contains("mode"))
            assertFalse(payload["required"]!!.jsonArray.map { it.jsonPrimitive.content }.contains("missing"))
            assertEquals(
                listOf("fast", "safe"),
                payload["properties"]!!.jsonObject["mode"]!!.jsonObject["enum"]!!.jsonArray
                    .map { it.jsonPrimitive.content },
            )
        }
    }

    @Test
    fun `Code Assist request path uses sanitized schemas instead of the original schema`() {
        val body = googleProvider.buildCompletionRequestBody(
            messages = listOf(UIMessage.user("call a tool")),
            params = TextGenerationParams(
                model = Model(modelId = "claude-compatible", abilities = listOf(ModelAbility.TOOL)),
                tools = listOf(complexSchemaTool("schema_tool")),
            ),
            safetyCategories = emptyList(),
            sanitizeToolSchemas = true,
        )
        val schema = body["tools"]!!.jsonArray.single()
            .jsonObject["functionDeclarations"]!!.jsonArray.single()
            .jsonObject["parameters"]!!.jsonObject
        assertFalse(schema.containsKey("${'$'}schema"))
        assertFalse(schema.toString().contains("anyOf"))
        assertFalse(schema.toString().contains("additionalProperties"))
    }

    private fun complexSchemaTool(name: String) = Tool(
        name = name,
        description = "complex schema",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("payload", buildJsonObject {
                        put("type", "object")
                        put("${'$'}schema", "https://json-schema.org/draft/2020-12/schema")
                        putJsonArray("required") {
                            add("mode")
                            add("missing")
                        }
                        put("properties", buildJsonObject {
                            put("mode", buildJsonObject {
                                put("type", "string")
                                putJsonArray("enum") {
                                    add("fast")
                                    add("safe")
                                }
                            })
                            put("values", buildJsonObject {
                                put("type", "array")
                                put("items", buildJsonObject {
                                    putJsonArray("anyOf") {
                                        add(buildJsonObject { put("type", "string") })
                                        add(buildJsonObject { put("type", "integer") })
                                    }
                                })
                                put("additionalProperties", false)
                            })
                        })
                    })
                },
                required = listOf("payload"),
            )
        },
        execute = { emptyList() },
    )

    private fun assertNoUnsupportedSchemaKeywords(element: JsonElement) {
        when (element) {
            is JsonObject -> {
                val unsupported = setOf(
                    "anyOf", "oneOf", "allOf", "not", "if", "then", "else", "${'$'}schema",
                    "${'$'}id", "${'$'}ref", "${'$'}defs", "additionalProperties", "patternProperties",
                )
                assertTrue(element.keys.intersect(unsupported).isEmpty())
                element.values.forEach(::assertNoUnsupportedSchemaKeywords)
            }
            is JsonArray -> element.forEach(::assertNoUnsupportedSchemaKeywords)
            else -> Unit
        }
    }

    @Test
    fun `a budget below Claude's floor is raised to 1024`() {
        val raised = invokeRaiseThinkingBudget(requestWithBudget(1000))
        assertEquals(1024, budgetOf(raised))
    }

    @Test
    fun `a budget of 0 (reasoning off) is left untouched`() {
        val raised = invokeRaiseThinkingBudget(requestWithBudget(0))
        assertEquals(0, budgetOf(raised))
    }

    @Test
    fun `a budget already above the floor is left untouched`() {
        val raised = invokeRaiseThinkingBudget(requestWithBudget(8000))
        assertEquals(8000, budgetOf(raised))
    }

    @Test
    fun `the clamp runs before the max-tokens raise so the raise sees 1024, not 1000`() {
        val clamped = invokeRaiseThinkingBudget(requestWithBudget(1000))
        val finalRequest = invokeRaiseMaxTokens(clamped)
        val maxTokens = finalRequest["generationConfig"]?.jsonObject
            ?.get("maxOutputTokens")?.jsonPrimitive?.intOrNull
        // 1024 (clamped budget) + THINKING_ANSWER_HEADROOM (8192); if the clamp ran after this
        // raise instead of before it, the result would be 1000 + 8192 = 9192.
        assertEquals(1024 + 8192, maxTokens)
    }

    // --- GPT-OSS thinkingConfig stripping ---------------------------------------------------

    @Test
    fun `a GPT-OSS request has no thinkingConfig after full post-processing`() {
        val stripped = invokeStripThinkingConfigForGptOss("gemini-gpt-oss-120b", requestWithBudget(8000))
        val finalRequest = invokeRaiseMaxTokens(invokeRaiseThinkingBudget(stripped))
        assertNull(finalRequest["generationConfig"]?.jsonObject?.get("thinkingConfig"))
    }

    @Test
    fun `GPT-OSS matching is case-insensitive`() {
        val stripped = invokeStripThinkingConfigForGptOss("GPT-OSS-20B", requestWithBudget(8000))
        assertNull(stripped["generationConfig"]?.jsonObject?.get("thinkingConfig"))
    }

    @Test
    fun `a Claude request keeps its thinkingConfig untouched`() {
        val request = requestWithBudget(8000)
        val stripped = invokeStripThinkingConfigForGptOss("gemini-claude-opus-4-6-thinking", request)
        assertEquals(8000, budgetOf(stripped))
    }

    @Test
    fun `a Claude 5-5 request has no thinkingConfig after post-processing`() {
        val stripped = invokeStripThinkingConfigForGptOss("claude-opus-5-5-high", requestWithBudget(8000))
        val finalRequest = invokeRaiseMaxTokens(invokeRaiseThinkingBudget(stripped))
        assertNull(finalRequest["generationConfig"]?.jsonObject?.get("thinkingConfig"))
    }
}
