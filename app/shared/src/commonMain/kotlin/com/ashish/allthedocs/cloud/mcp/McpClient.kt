package com.ashish.allthedocs.cloud.mcp

import com.ashish.allthedocs.cloud.CloudAuthException
import com.ashish.allthedocs.cloud.CloudException
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

class McpClient(
    private val httpClient: HttpClient,
    private val name: String,
    private val url: String,
    private val accessToken: suspend () -> String?,
) {
    private val mutex = Mutex()
    private var sessionId: String? = null
    private var initialized = false
    private var nextId = 0

    suspend fun listTools(): List<McpTool> = mutex.withLock {
        val tools = mutableListOf<McpTool>()
        var cursor: String? = null
        repeat(MAX_PAGES) {
            val result = call("tools/list", buildJsonObject { cursor?.let { put("cursor", it) } })
            result.array("tools").mapNotNullTo(tools) { tool ->
                tool.string("name")?.let { McpTool(it, tool.string("description"), tool["inputSchema"] as? JsonObject) }
            }
            cursor = result.string("nextCursor") ?: return@withLock tools
        }
        tools
    }

    suspend fun callTool(toolName: String, arguments: JsonObject): McpToolResult = mutex.withLock {
        val result = call(
            "tools/call",
            buildJsonObject {
                put("name", toolName)
                put("arguments", arguments)
            },
        )
        McpToolResult(
            content = result.array("content").mapNotNull(::parseContent),
            structuredContent = result["structuredContent"] as? JsonObject,
            isError = (result["isError"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }

    suspend fun readResource(uri: String): List<McpResourceContent> = mutex.withLock {
        call("resources/read", buildJsonObject { put("uri", uri) }).array("contents").map(::parseResource)
    }

    private suspend fun call(method: String, params: JsonObject): JsonObject {
        ensureInitialized()
        return try {
            request(method, params)
        } catch (e: SessionLostException) {
            initialized = false
            ensureInitialized()
            request(method, params)
        }
    }

    private suspend fun ensureInitialized() {
        if (initialized) return
        sessionId = null
        request(
            "initialize",
            buildJsonObject {
                put("protocolVersion", PROTOCOL_VERSION)
                put("capabilities", buildJsonObject {})
                put(
                    "clientInfo",
                    buildJsonObject {
                        put("name", CLIENT_NAME)
                        put("version", CLIENT_VERSION)
                    },
                )
            },
        )
        post(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", "notifications/initialized")
            },
        )
        initialized = true
    }

    private suspend fun request(method: String, params: JsonObject): JsonObject {
        val id = ++nextId
        val response = post(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            },
        )
        val body = response.bodyAsText()
        val messages = if (response.contentType()?.match(ContentType.Text.EventStream) == true) sseData(body) else listOf(body)
        val reply = messages.asSequence()
            .flatMap { parseMessages(it) }
            .firstOrNull { (it["id"] as? JsonPrimitive)?.contentOrNull == id.toString() }
            ?: throw CloudException("$name didn't answer the $method request")
        (reply["error"] as? JsonObject)?.let { error ->
            throw McpRpcException("$name: ${error.string("message") ?: "$method failed"}", (error["code"] as? JsonPrimitive)?.intOrNull)
        }
        return reply["result"] as? JsonObject ?: throw CloudException("$name sent an unexpected response")
    }

    private suspend fun post(message: JsonObject): HttpResponse {
        val token = accessToken()
        val hadSession = sessionId != null
        val response = try {
            httpClient.post(url) {
                header(HttpHeaders.Accept, ACCEPT)
                header(PROTOCOL_HEADER, PROTOCOL_VERSION)
                sessionId?.let { header(SESSION_HEADER, it) }
                token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                setBody(TextContent(message.toString(), ContentType.Application.Json))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw CloudException("Couldn't reach $name", e)
        }
        response.headers[SESSION_HEADER]?.let { sessionId = it }
        when {
            response.status.isSuccess() -> Unit
            response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden ->
                throw CloudAuthException("$name access expired or wasn't granted. Connect it again.")
            response.status == HttpStatusCode.NotFound && hadSession -> throw SessionLostException()
            else -> throw CloudException("$name request failed (${response.status.value})")
        }
        return response
    }

    private fun parseMessages(text: String): List<JsonObject> =
        when (val element = runCatching { json.parseToJsonElement(text) }.getOrNull()) {
            is JsonObject -> listOf(element)
            is JsonArray -> element.filterIsInstance<JsonObject>()
            else -> emptyList()
        }

    private fun parseContent(item: JsonObject): McpContent? = when (item.string("type")) {
        "text" -> (item["text"] as? JsonPrimitive)?.contentOrNull?.let { McpContent.Text(it) }
        "image" -> item.string("data")?.let { McpContent.Image(it, item.string("mimeType")) }
        "resource_link" -> item.string("uri")?.let {
            McpContent.ResourceLink(it, item.string("name"), item.string("mimeType"), item.long("size"), item.string("description"))
        }
        "resource" -> (item["resource"] as? JsonObject)?.let { McpContent.Resource(parseResource(it)) }
        else -> null
    }

    private fun parseResource(item: JsonObject) =
        McpResourceContent(item.string("uri"), item.string("mimeType"), (item["text"] as? JsonPrimitive)?.contentOrNull, item.string("blob"))

    private class SessionLostException : Exception()

    companion object {
        const val PROTOCOL_VERSION = "2025-06-18"
        const val PROTOCOL_HEADER = "MCP-Protocol-Version"
        const val SESSION_HEADER = "Mcp-Session-Id"
        const val ACCEPT = "application/json, text/event-stream"
        private const val CLIENT_NAME = "AllTheDocs"
        private const val CLIENT_VERSION = "1.0"
        private const val MAX_PAGES = 10
        private val json = Json { ignoreUnknownKeys = true }

        internal fun sseData(body: String): List<String> {
            val messages = mutableListOf<String>()
            val data = StringBuilder()
            fun flush() {
                if (data.isNotEmpty()) messages += data.toString()
                data.clear()
            }
            for (line in body.lineSequence()) {
                when {
                    line.isEmpty() -> flush()
                    line.startsWith("data:") -> {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(line.removePrefix("data:").removePrefix(" "))
                    }
                }
            }
            flush()
            return messages
        }
    }
}
