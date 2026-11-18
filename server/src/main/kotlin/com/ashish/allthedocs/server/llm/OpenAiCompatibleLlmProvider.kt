package com.ashish.allthedocs.server.llm

import com.ashish.allthedocs.server.LlmOutputException
import com.ashish.allthedocs.server.LlmUnavailableException
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.io.encoding.Base64

class OpenAiCompatibleLlmProvider(
    private val config: LlmConfig,
    private val httpClient: HttpClient = defaultHttpClient(),
) : LlmProvider {

    override suspend fun complete(request: LlmRequest): String {
        val body = buildRequestBody(request)
        val response = try {
            httpClient.post("${config.baseUrl}/chat/completions") {
                bearerAuth(config.apiKey)
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw LlmUnavailableException("LLM provider is unreachable: ${e::class.simpleName}")
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val providerMessage = errorMessageOf(text)
            throw when (response.status) {
                HttpStatusCode.BadRequest -> LlmOutputException("LLM provider rejected the request: $providerMessage")
                HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden ->
                    LlmUnavailableException("LLM provider rejected the API key")
                HttpStatusCode.TooManyRequests -> LlmUnavailableException("LLM provider rate limit reached, try again shortly")
                else -> LlmUnavailableException("LLM provider error ${response.status.value}: $providerMessage")
            }
        }
        return contentOf(text)
    }

    internal fun buildRequestBody(request: LlmRequest): JsonObject {
        val model = when {
            request.images.isNotEmpty() -> config.visionModel
            request.webSearch -> config.searchModel
            else -> config.textModel
        }
        return buildJsonObject {
            put("model", model)
            put("temperature", 0.2)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", request.systemPrompt)
                }
                addJsonObject {
                    put("role", "user")
                    if (request.images.isEmpty()) {
                        put("content", request.userText)
                    } else {
                        putJsonArray("content") {
                            addJsonObject {
                                put("type", "text")
                                put("text", request.userText)
                            }
                            request.images.take(MAX_IMAGES).forEach { image ->
                                addJsonObject {
                                    put("type", "image_url")
                                    putJsonObject("image_url") {
                                        put("url", "data:image/jpeg;base64,${Base64.encode(image)}")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (request.webSearch && request.images.isEmpty()) {
                putJsonArray("tools") {
                    addJsonObject { put("type", "browser_search") }
                }
            } else if (request.jsonMode) {
                putJsonObject("response_format") { put("type", "json_object") }
            }
        }
    }

    private fun contentOf(responseText: String): String {
        val content = runCatching {
            Json.parseToJsonElement(responseText).jsonObject["choices"]!!.jsonArray[0]
                .jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.contentOrNull
        }.getOrNull()
        if (content.isNullOrBlank()) throw LlmOutputException("LLM provider returned an empty response")
        return content
    }

    private fun errorMessageOf(responseText: String): String =
        runCatching {
            Json.parseToJsonElement(responseText).jsonObject["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
        }.getOrNull()?.take(300) ?: "unknown error"

    companion object {
        const val MAX_IMAGES = 3

        fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 120_000
                connectTimeoutMillis = 15_000
            }
            expectSuccess = false
        }
    }
}
