package com.ashish.allthedocs.cloud.mcp

import com.ashish.allthedocs.cloud.CloudException
import com.ashish.allthedocs.cloud.CloudFile
import com.ashish.allthedocs.cloud.McpServer
import com.ashish.allthedocs.data.ApiClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpSearchServiceTest {
    private val server = McpServer("s1", "Dropbox", "https://mcp.example.com/mcp")
    private val requests = mutableListOf<HttpRequestData>()
    private val calls = mutableListOf<JsonObject>()
    private val reads = mutableListOf<String>()
    private var tools = "[]"
    private var onCall: (String, JsonObject) -> String = { _, _ -> """{"content":[]}""" }
    private var onRead: (String) -> String? = { null }

    private val service = McpSearchService(
        ApiClient.createHttpClient(
            MockEngine { request ->
                requests += request
                if (request.url.host == "dl.example.com") {
                    assertNull(request.headers[HttpHeaders.Authorization])
                    respond(byteArrayOf(7, 7), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/pdf"))
                } else {
                    val body = Json.parseToJsonElement((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
                    val id = body["id"]
                    val params = body["params"]?.jsonObject ?: JsonObject(emptyMap())
                    when (body["method"]!!.jsonPrimitive.content) {
                        "initialize" -> json(
                            """{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-06-18","capabilities":{},"serverInfo":{"name":"t","version":"1"}}}""",
                        )
                        "notifications/initialized" -> respond("", HttpStatusCode.Accepted)
                        "tools/list" -> json("""{"jsonrpc":"2.0","id":$id,"result":{"tools":$tools}}""")
                        "tools/call" -> {
                            calls += params
                            val result = onCall(params["name"]!!.jsonPrimitive.content, params["arguments"]!!.jsonObject)
                            json("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
                        }
                        "resources/read" -> {
                            val uri = params["uri"]!!.jsonPrimitive.content
                            reads += uri
                            onRead(uri)?.let { json("""{"jsonrpc":"2.0","id":$id,"result":$it}""") }
                                ?: json("""{"jsonrpc":"2.0","id":$id,"error":{"code":-32601,"message":"Method not found"}}""")
                        }
                        else -> error("unexpected ${body["method"]}")
                    }
                }
            },
        ),
    ) { "token" }

    private fun MockRequestHandleScope.json(body: String) = respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun file(id: String, locators: Map<String, String>) = CloudFile(server, id, null, "x.pdf", "application/pdf", null, null, null, locators)

    @Test
    fun searchUsesSearchToolAndStructuredContent() = runTest {
        tools = """[
          {"name":"get_file_metadata","inputSchema":{"type":"object","properties":{"file_id":{"type":"string"}}}},
          {"name":"search_files","inputSchema":{"type":"object","properties":{"query":{"type":"string"},"folder":{"type":"string"},"max_results":{"type":"integer"}},"required":["query"]}}
        ]"""
        onCall = { _, _ ->
            """{"content":[{"type":"text","text":"3 results"}],"structuredContent":{"files":[
              {"id":"f1","name":"Form16_FY25.pdf","mimeType":"application/pdf","size":12345,"modifiedTime":"2026-05-01T10:00:00Z"},
              {"id":"f2","name":"notes.docx","mimeType":"application/vnd.openxmlformats-officedocument.wordprocessingml.document"},
              {"id":"f3","name":"scan.jpg","size":"99999999"}]}}"""
        }

        val files = service.search(server, listOf("Form 16", "Form16"))

        assertEquals(2, calls.size)
        assertEquals("search_files", calls[0]["name"]!!.jsonPrimitive.content)
        val arguments = calls[0]["arguments"]!!.jsonObject
        assertEquals("Form 16", arguments["query"]!!.jsonPrimitive.content)
        assertEquals("20", arguments["max_results"]!!.jsonPrimitive.content)
        assertEquals(2, arguments.size)
        val file = files.single()
        assertEquals("f1", file.id)
        assertEquals(server, file.provider)
        assertEquals("application/pdf", file.mimeType)
        assertEquals(12345L, file.sizeBytes)
        assertEquals("Dropbox", file.context)
        assertEquals("2026-05-01T10:00:00Z", file.modified)
        assertEquals(mapOf("id" to "f1"), file.locators)
        assertEquals(1, requests.count { (it.body as OutgoingContent.ByteArrayContent).bytes().decodeToString().contains("tools/list") })
    }

    @Test
    fun searchParsesTextJsonWrappersAndResourceLinks() = runTest {
        tools = """[{"name":"Search","inputSchema":{"type":"object","properties":{"q":{"type":"string"},"file_type":{"type":"string"}},"required":["q"]}}]"""
        onCall = { _, _ ->
            """{"content":[
              {"type":"text","text":"{\"matches\":[{\"match_type\":\"filename\",\"metadata\":{\"metadata\":{\".tag\":\"file\",\"id\":\"id:abc\",\"name\":\"HDFC statement.pdf\",\"path_display\":\"/Finance/HDFC statement.pdf\",\"size\":5000,\"server_modified\":\"2026-01-01T00:00:00Z\"}}}]}"},
              {"type":"resource_link","uri":"https://files.example.com/stmt.png","name":"stmt.png","mimeType":"image/png","size":60000},
              {"type":"resource_link","uri":"https://files.example.com/notes.txt","name":"notes.txt","mimeType":"text/plain"}
            ]}"""
        }

        val files = service.search(server, listOf("bank statement"))

        assertEquals("bank statement", calls.single()["arguments"]!!.jsonObject["q"]!!.jsonPrimitive.content)
        assertEquals(listOf("id:abc", "https://files.example.com/stmt.png"), files.map { it.id })
        assertEquals("Dropbox/Finance", files[0].context)
        assertEquals("/Finance/HDFC statement.pdf", files[0].locators["path"])
        assertEquals(5000L, files[0].sizeBytes)
        assertEquals(mapOf("uri" to "https://files.example.com/stmt.png"), files[1].locators)
    }

    @Test
    fun downloadReadsResourceBlobFirst() = runTest {
        onRead = { uri -> """{"contents":[{"uri":"$uri","mimeType":"application/pdf","blob":"AQID"}]}""" }

        val bytes = service.download(file("x", mapOf("id" to "x", "uri" to "res://x")))

        assertContentEquals(byteArrayOf(1, 2, 3), bytes)
        assertEquals(listOf("res://x"), reads)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun downloadFallsBackToDownloadLinkTool() = runTest {
        tools = """[
          {"name":"GetFileMetadata","inputSchema":{"type":"object","properties":{"path":{"type":"string"}}}},
          {"name":"GetFileContent","inputSchema":{"type":"object","properties":{"path":{"type":"string"}}}},
          {"name":"DownloadLink","inputSchema":{"type":"object","properties":{"path":{"type":"string"}}}}
        ]"""
        onCall = { name, _ ->
            if (name == "DownloadLink") """{"content":[{"type":"text","text":"https://dl.example.com/x.pdf?dl=1"}]}""" else error("wrong tool $name")
        }

        val bytes = service.download(file("id:abc", mapOf("id" to "id:abc", "path" to "/Finance/x.pdf", "uri" to "res://x")))

        assertContentEquals(byteArrayOf(7, 7), bytes)
        assertEquals(listOf("res://x"), reads)
        val call = calls.single()
        assertEquals("DownloadLink", call["name"]!!.jsonPrimitive.content)
        assertEquals("/Finance/x.pdf", call["arguments"]!!.jsonObject["path"]!!.jsonPrimitive.content)
        assertEquals("dl.example.com", requests.last().url.host)
    }

    @Test
    fun downloadToolReturningEmbeddedResource() = runTest {
        tools = """[
          {"name":"download_file_content","inputSchema":{"type":"object","properties":{"file_id":{"type":"string"}},"required":["file_id"]}},
          {"name":"read_file_content","inputSchema":{"type":"object","properties":{"file_id":{"type":"string"}}}}
        ]"""
        onCall = { name, arguments ->
            assertEquals("download_file_content", name)
            assertEquals("f1", arguments["file_id"]!!.jsonPrimitive.content)
            """{"content":[{"type":"resource","resource":{"uri":"gdrive://f1","mimeType":"application/pdf","blob":"AQID"}}]}"""
        }

        val bytes = service.download(file("f1", mapOf("id" to "f1")))

        assertContentEquals(byteArrayOf(1, 2, 3), bytes)
        assertTrue(reads.isEmpty())
    }

    @Test
    fun toolErrorsAndMissingToolsAreReported() = runTest {
        tools = """[{"name":"search","inputSchema":{"type":"object","properties":{"query":{"type":"string"}}}}]"""
        onCall = { _, _ -> """{"content":[{"type":"text","text":"Rate limited"}],"isError":true}""" }

        val error = assertFailsWith<CloudException> { service.search(server, listOf("pan")) }
        assertEquals("Dropbox: Rate limited", error.message)

        val noTool = assertFailsWith<CloudException> { service.download(file("f1", emptyMap())) }
        assertTrue(noTool.message!!.contains("download tool"))
    }

    @Test
    fun missingSearchToolIsReported() = runTest {
        tools = """[{"name":"list_files","inputSchema":{"type":"object","properties":{}}}]"""

        val error = assertFailsWith<CloudException> { service.search(server, listOf("pan")) }

        assertTrue(error.message!!.contains("search tool"))
    }

    @Test
    fun toolSelectionHeuristics() {
        fun tool(name: String, schema: String) = McpTool(name, null, Json.parseToJsonElement(schema).jsonObject)
        val search = tool(
            "search_documents",
            """{"type":"object","properties":{"folder":{"type":"string"},"text":{"type":["string","null"]},"limit":{"type":"number"}},"required":["text"]}""",
        )
        assertEquals("text", McpSearchService.queryParameter(search))
        assertEquals("limit", McpSearchService.limitParameter(search))
        assertEquals(
            "needle",
            McpSearchService.queryParameter(
                tool("find", """{"type":"object","properties":{"needle":{"type":"string"},"haystack":{"type":"integer"}},"required":["needle"]}"""),
            ),
        )
        assertNull(McpSearchService.queryParameter(tool("x", """{"type":"object","properties":{"a":{"type":"string"},"b":{"type":"string"}}}""")))

        val download = McpSearchService.downloadTool(
            listOf(
                tool("get_file_metadata", """{"properties":{"id":{"type":"string"}}}"""),
                tool("read_file_content", """{"properties":{"id":{"type":"string"}}}"""),
                tool("download_file", """{"properties":{"uri":{"type":"string"}}}"""),
            ),
        )
        assertEquals("download_file", download!!.name)
        assertEquals("uri", McpSearchService.locatorParameter(download))
        assertEquals("uri", McpSearchService.locatorKey("uri"))
        assertEquals("path", McpSearchService.locatorKey("path_display"))
        assertEquals("id", McpSearchService.locatorKey("file_id"))

        assertContentEquals(byteArrayOf(1, 2, 3), McpSearchService.decodeBase64("AQ ID"))
        assertContentEquals(byteArrayOf(0xFB.toByte(), 0xFF.toByte(), 0x3E, 0xFF.toByte()), McpSearchService.decodeBase64("-_8-_w"))
    }
}
