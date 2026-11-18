package com.ashish.allthedocs.cloud.mcp

import com.ashish.allthedocs.cloud.CloudAuthException
import com.ashish.allthedocs.data.ApiClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpClientTest {
    private val requests = mutableListOf<Pair<HttpRequestData, JsonObject>>()
    private var initializeCount = 0
    private var unauthorized = false

    private fun client(
        token: String? = "tok",
        onRequest: suspend MockRequestHandleScope.(HttpRequestData, JsonObject) -> HttpResponseData,
    ): McpClient {
        val engine = MockEngine { request ->
            val body = Json.parseToJsonElement((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
            requests += request to body
            when {
                unauthorized -> respond("", HttpStatusCode.Unauthorized)
                body.method() == "initialize" -> {
                    initializeCount++
                    respond(
                        """{"jsonrpc":"2.0","id":${body["id"]},"result":{"protocolVersion":"2025-06-18","capabilities":{},"serverInfo":{"name":"t","version":"1"}}}""",
                        HttpStatusCode.OK,
                        headersOf("Content-Type" to listOf("application/json"), "Mcp-Session-Id" to listOf("session-1")),
                    )
                }
                body.method() == "notifications/initialized" -> respond("", HttpStatusCode.Accepted)
                else -> onRequest(request, body)
            }
        }
        return McpClient(ApiClient.createHttpClient(engine), "Test", "https://mcp.example.com/mcp") { token }
    }

    private fun JsonObject.method() = this["method"]?.jsonPrimitive?.content

    private fun MockRequestHandleScope.result(body: JsonObject, result: String) = respond(
        """{"jsonrpc":"2.0","id":${body["id"]},"result":$result}""",
        HttpStatusCode.OK,
        headersOf(HttpHeaders.ContentType, "application/json"),
    )

    @Test
    fun initializesSendsHeadersAndListsTools() = runTest {
        val client = client { _, body ->
            result(
                body,
                """{"tools":[{"name":"search","description":"d","inputSchema":{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}}]}""",
            )
        }

        val tools = client.listTools()

        assertEquals(listOf("search"), tools.map { it.name })
        assertEquals("d", tools.single().description)
        assertEquals(setOf("query"), tools.single().inputSchema!!["properties"]!!.jsonObject.keys)
        assertEquals(listOf("initialize", "notifications/initialized", "tools/list"), requests.map { it.second.method() })
        val init = requests[0].first
        assertEquals("application/json, text/event-stream", init.headers[HttpHeaders.Accept])
        assertEquals("2025-06-18", init.headers["MCP-Protocol-Version"])
        assertEquals("Bearer tok", init.headers[HttpHeaders.Authorization])
        assertNull(init.headers["Mcp-Session-Id"])
        assertEquals("application/json", init.body.contentType.toString())
        assertEquals("2025-06-18", requests[0].second["params"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals("session-1", requests[1].first.headers["Mcp-Session-Id"])
        assertEquals("session-1", requests[2].first.headers["Mcp-Session-Id"])
    }

    @Test
    fun paginatesToolList() = runTest {
        val client = client { _, body ->
            val cursor = body["params"]!!.jsonObject["cursor"]?.jsonPrimitive?.content
            if (cursor == null) {
                result(body, """{"tools":[{"name":"a"}],"nextCursor":"c2"}""")
            } else {
                result(body, """{"tools":[{"name":"b"}]}""")
            }
        }

        assertEquals(listOf("a", "b"), client.listTools().map { it.name })
        assertEquals("c2", requests.last().second["params"]!!.jsonObject["cursor"]!!.jsonPrimitive.content)
    }

    @Test
    fun parsesEventStreamResponses() = runTest {
        val client = client { _, body ->
            respond(
                "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\",\"params\":{}}\n\n" +
                    ": keep-alive\n\n" +
                    "id: 7\ndata: {\"jsonrpc\":\"2.0\",\"id\":${body["id"]},\ndata: \"result\":{\"content\":[" +
                    "{\"type\":\"text\",\"text\":\"hello\"}," +
                    "{\"type\":\"resource_link\",\"uri\":\"file:///a.pdf\",\"name\":\"a.pdf\",\"mimeType\":\"application/pdf\",\"size\":12}," +
                    "{\"type\":\"image\",\"data\":\"AQID\",\"mimeType\":\"image/png\"}," +
                    "{\"type\":\"resource\",\"resource\":{\"uri\":\"x://1\",\"blob\":\"AQID\"}}" +
                    "],\"structuredContent\":{\"ok\":true},\"isError\":false}}\n\n",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }

        val result = client.callTool("search", buildJsonObject { put("query", "x") })

        assertEquals("hello", (result.content[0] as McpContent.Text).text)
        val link = result.content[1] as McpContent.ResourceLink
        assertEquals("file:///a.pdf", link.uri)
        assertEquals(12L, link.size)
        assertEquals("AQID", (result.content[2] as McpContent.Image).data)
        assertEquals("x://1", (result.content[3] as McpContent.Resource).resource.uri)
        assertEquals("true", result.structuredContent!!["ok"]!!.jsonPrimitive.content)
        assertFalse(result.isError)
        val call = requests.last().second
        assertEquals("tools/call", call.method())
        assertEquals("search", call["params"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("x", call["params"]!!.jsonObject["arguments"]!!.jsonObject["query"]!!.jsonPrimitive.content)
    }

    @Test
    fun sseDataJoinsMultilineEvents() {
        assertEquals(listOf("a\nb", "c"), McpClient.sseData("event: x\ndata: a\ndata: b\n\n: comment\n\ndata:c\n"))
    }

    @Test
    fun unauthorizedBecomesAuthException() = runTest {
        unauthorized = true
        val client = client { _, body -> result(body, "{}") }

        assertFailsWith<CloudAuthException> { client.listTools() }
    }

    @Test
    fun rpcErrorCarriesCode() = runTest {
        val client = client { _, body ->
            respond(
                """{"jsonrpc":"2.0","id":${body["id"]},"error":{"code":-32601,"message":"Method not found"}}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val error = assertFailsWith<McpRpcException> { client.readResource("x://1") }

        assertEquals(-32601, error.code)
        assertTrue(error.message!!.contains("Method not found"))
    }

    @Test
    fun lostSessionReinitializesOnce() = runTest {
        var listCalls = 0
        val client = client { _, body -> if (++listCalls == 1) respond("", HttpStatusCode.NotFound) else result(body, """{"tools":[]}""") }

        assertEquals(emptyList(), client.listTools())

        assertEquals(2, initializeCount)
        assertEquals(
            listOf("initialize", "notifications/initialized", "tools/list", "initialize", "notifications/initialized", "tools/list"),
            requests.map { it.second.method() },
        )
        assertNull(requests[3].first.headers["Mcp-Session-Id"])
    }

    @Test
    fun readResourceParsesContents() = runTest {
        val client = client { _, body -> result(body, """{"contents":[{"uri":"x://1","mimeType":"application/pdf","blob":"AQID"}]}""") }

        val contents = client.readResource("x://1")

        assertEquals("AQID", contents.single().blob)
        assertEquals("application/pdf", contents.single().mimeType)
        assertEquals("x://1", requests.last().second["params"]!!.jsonObject["uri"]!!.jsonPrimitive.content)
    }

    @Test
    fun noTokenSendsNoAuthorizationHeader() = runTest {
        val client = client(token = null) { _, body -> result(body, """{"tools":[]}""") }

        client.listTools()

        assertNull(requests[0].first.headers[HttpHeaders.Authorization])
    }
}
