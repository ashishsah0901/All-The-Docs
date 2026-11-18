package com.ashish.allthedocs.server.llm

import com.ashish.allthedocs.server.LlmOutputException
import com.ashish.allthedocs.server.LlmUnavailableException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenAiCompatibleLlmProviderTest {
    private val config = LlmConfig("https://llm.test/v1", "key", "text-model", "search-model", "vision-model")
    private val okBody = """{"choices":[{"message":{"role":"assistant","content":"{\"ok\":true}"}}]}"""

    private fun provider(status: HttpStatusCode = HttpStatusCode.OK, body: String = okBody, captured: MutableList<JsonObject>? = null) =
        OpenAiCompatibleLlmProvider(
            config,
            HttpClient(
                MockEngine { request ->
                    assertEquals("https://llm.test/v1/chat/completions", request.url.toString())
                    assertEquals("Bearer key", request.headers[HttpHeaders.Authorization])
                    captured?.add(Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject)
                    respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
                },
            ),
        )

    @Test
    fun textRequestUsesJsonMode() = runTest {
        val captured = mutableListOf<JsonObject>()
        assertEquals("{\"ok\":true}", provider(captured = captured).complete(LlmRequest("sys", "user")))
        val body = captured.single()
        assertEquals("text-model", body["model"]!!.jsonPrimitive.content)
        assertEquals("json_object", body["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertNull(body["tools"])
    }

    @Test
    fun searchRequestUsesBrowserSearchWithoutResponseFormat() = runTest {
        val captured = mutableListOf<JsonObject>()
        provider(captured = captured).complete(LlmRequest("sys", "user", jsonMode = false, webSearch = true))
        val body = captured.single()
        assertEquals("search-model", body["model"]!!.jsonPrimitive.content)
        assertEquals("browser_search", body["tools"]!!.jsonArray.single().jsonObject["type"]!!.jsonPrimitive.content)
        assertNull(body["response_format"])
    }

    @Test
    fun visionRequestSendsAtMostThreeImages() = runTest {
        val captured = mutableListOf<JsonObject>()
        val images = List(5) { byteArrayOf(1, 2, 3) }
        provider(captured = captured).complete(LlmRequest("sys", "user", images = images))
        val body = captured.single()
        assertEquals("vision-model", body["model"]!!.jsonPrimitive.content)
        val content = body["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray
        assertEquals(4, content.size)
        val url = content[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
        assertTrue(url.startsWith("data:image/jpeg;base64,"))
    }

    @Test
    fun mapsErrorStatuses() = runTest {
        assertFailsWith<LlmUnavailableException> {
            provider(HttpStatusCode.Unauthorized, """{"error":{"message":"bad key"}}""").complete(LlmRequest("s", "u"))
        }
        assertFailsWith<LlmUnavailableException> {
            provider(HttpStatusCode.TooManyRequests, "{}").complete(LlmRequest("s", "u"))
        }
        val error = assertFailsWith<LlmOutputException> {
            provider(HttpStatusCode.BadRequest, """{"error":{"message":"json_validate_failed"}}""").complete(LlmRequest("s", "u"))
        }
        assertTrue(error.message!!.contains("json_validate_failed"))
    }

    @Test
    fun emptyContentIsOutputError() = runTest {
        assertFailsWith<LlmOutputException> {
            provider(body = """{"choices":[{"message":{"content":""}}]}""").complete(LlmRequest("s", "u"))
        }
    }

    @Test
    fun configReadsEnvironmentWithGroqFallback() {
        assertNull(LlmConfig.fromEnvironment { null })
        val config = LlmConfig.fromEnvironment { if (it == "GROQ_API_KEY") "gsk" else null }!!
        assertEquals("gsk", config.apiKey)
        assertEquals(LlmConfig.DEFAULT_BASE_URL, config.baseUrl)
        val custom = LlmConfig.fromEnvironment {
            mapOf("LLM_API_KEY" to "k", "GROQ_API_KEY" to "g", "LLM_BASE_URL" to "https://x/v1/", "LLM_TEXT_MODEL" to "m")[it]
        }!!
        assertEquals("k", custom.apiKey)
        assertEquals("https://x/v1", custom.baseUrl)
        assertEquals("m", custom.textModel)
    }
}
