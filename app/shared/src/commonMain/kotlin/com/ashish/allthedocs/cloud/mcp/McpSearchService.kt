package com.ashish.allthedocs.cloud.mcp

import com.ashish.allthedocs.api.ApiRoutes
import com.ashish.allthedocs.cloud.CloudException
import com.ashish.allthedocs.cloud.CloudFile
import com.ashish.allthedocs.cloud.CloudSearchService
import com.ashish.allthedocs.cloud.McpServer
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64

class McpSearchService(
    private val httpClient: HttpClient,
    private val tokens: McpTokenSource,
) {
    private val mutex = Mutex()
    private val clients = mutableMapOf<String, McpClient>()
    private val toolCache = mutableMapOf<String, List<McpTool>>()

    suspend fun search(server: McpServer, keywords: List<String>, limit: Int = 20): List<CloudFile> {
        val terms = keywords.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (terms.isEmpty() || limit <= 0) return emptyList()
        val client = clientFor(server)
        val tool = searchTool(toolsFor(server, client)) ?: throw CloudException("${server.name} doesn't offer a search tool")
        val queryParameter = queryParameter(tool) ?: throw CloudException("${server.name}'s ${tool.name} tool doesn't take a text query")
        val limitParameter = limitParameter(tool)
        return terms.flatMap { term ->
            val arguments = buildJsonObject {
                put(queryParameter, term)
                limitParameter?.let { put(it, limit) }
            }
            filesFrom(server, call(server, client, tool.name, arguments))
        }
            .filter { it.sizeBytes == null || it.sizeBytes <= ApiRoutes.MAX_UPLOAD_BYTES }
            .distinctBy { it.id }
            .sortedByDescending { CloudSearchService.epochMillisOf(it.modified) }
            .take(limit)
    }

    suspend fun download(file: CloudFile): ByteArray {
        val server = file.provider as? McpServer ?: throw CloudException("${file.name} isn't from a connected app")
        val client = clientFor(server)
        file.locators[LOCATOR_URI]?.let { uri -> readBlob(client, uri)?.let { return checkedSize(it) } }
        val tool = downloadTool(toolsFor(server, client)) ?: throw CloudException("${server.name} doesn't offer a download tool")
        val parameter = locatorParameter(tool) ?: throw CloudException("${server.name}'s ${tool.name} tool doesn't take a file reference")
        val value = file.locators[locatorKey(parameter)] ?: file.locators[LOCATOR_ID] ?: file.id
        val result = call(server, client, tool.name, buildJsonObject { put(parameter, value) })
        val bytes = bytesFrom(client, result) ?: throw CloudException("${server.name} returned text instead of the file ${file.name}")
        return checkedSize(bytes)
    }

    private suspend fun clientFor(server: McpServer): McpClient = mutex.withLock {
        clients.getOrPut(server.id) { McpClient(httpClient, server.name, server.url) { tokens.accessToken(server) } }
    }

    private suspend fun toolsFor(server: McpServer, client: McpClient): List<McpTool> {
        mutex.withLock { toolCache[server.id] }?.let { return it }
        val tools = client.listTools()
        mutex.withLock { toolCache[server.id] = tools }
        return tools
    }

    private suspend fun call(server: McpServer, client: McpClient, toolName: String, arguments: JsonObject): McpToolResult {
        val result = client.callTool(toolName, arguments)
        if (result.isError) {
            val message = result.content.filterIsInstance<McpContent.Text>().firstOrNull()?.text?.trim()?.take(200)
            throw CloudException("${server.name}: ${message?.ifEmpty { null } ?: "$toolName failed"}")
        }
        return result
    }

    private suspend fun readBlob(client: McpClient, uri: String): ByteArray? {
        val contents = try {
            client.readResource(uri)
        } catch (e: McpRpcException) {
            return null
        }
        return contents.firstNotNullOfOrNull { it.blob }?.let(::decodeBase64)
    }

    private suspend fun bytesFrom(client: McpClient, result: McpToolResult): ByteArray? {
        for (item in result.content) {
            when (item) {
                is McpContent.Resource -> item.resource.blob?.let { return decodeBase64(it) }
                is McpContent.Image -> return decodeBase64(item.data)
                is McpContent.ResourceLink -> readBlob(client, item.uri)?.let { return it }
                is McpContent.Text -> linkIn(item.text)?.let { return fetchLink(it) }
            }
        }
        result.structuredContent?.let { structured -> linkIn(structured)?.let { return fetchLink(it) } }
        return null
    }

    private suspend fun fetchLink(url: String): ByteArray {
        val response = try {
            httpClient.get(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw CloudException("Couldn't download the file", e)
        }
        if (!response.status.isSuccess()) throw CloudException("Download link failed (${response.status.value})")
        return response.body()
    }

    private fun checkedSize(bytes: ByteArray): ByteArray {
        if (bytes.size > ApiRoutes.MAX_UPLOAD_BYTES) {
            throw CloudException("File is larger than ${ApiRoutes.MAX_UPLOAD_BYTES / (1024 * 1024)} MB")
        }
        return bytes
    }

    companion object {
        internal const val LOCATOR_ID = "id"
        internal const val LOCATOR_PATH = "path"
        internal const val LOCATOR_URI = "uri"
        internal const val LOCATOR_URL = "url"
        private const val MAX_DEPTH = 4
        private val json = Json { ignoreUnknownKeys = true }

        private val NAME_KEYS = listOf("name", "title", "filename", "file_name", "fileName", "displayName", "display_name")
        private val ID_KEYS = listOf("id", "file_id", "fileId", "document_id", "documentId", "item_id", "itemId")
        private val PATH_KEYS = listOf("path_display", "path", "path_lower", "file_path", "filePath")
        private val URL_KEYS = listOf("url", "webUrl", "web_url", "download_url", "downloadUrl", "link", "href", "webContentLink")
        private val MIME_KEYS = listOf("mimeType", "mime_type", "contentType", "content_type", "mime", "mediaType", "media_type")
        private val SIZE_KEYS = listOf("size", "sizeBytes", "size_bytes", "bytes", "fileSize", "file_size")
        private val MODIFIED_KEYS = listOf(
            "modifiedTime", "modified_time", "modified", "modifiedAt", "modified_at", "lastModified", "last_modified",
            "lastModifiedDateTime", "updatedAt", "updated_at", "server_modified", "client_modified", "createdTime", "created_time",
        )
        private val CONTEXT_KEYS = listOf("parent", "folder", "snippet", "description", "context", "summary")
        private val LIST_KEYS = listOf("results", "items", "files", "matches", "entries", "hits", "documents", "data", "value")
        private val NESTED_KEYS = listOf("metadata", "file", "item", "document", "resource", "result", "data", "response", "payload")
        private val LINK_KEYS = listOf(
            "url", "link", "download_url", "downloadUrl", "temporary_link", "temporaryLink", "href", "download_link", "downloadLink",
            "webContentLink",
        )
        private val QUERY_NAMES = listOf(
            "query", "q", "search", "search_query", "searchQuery", "text", "keywords", "keyword", "term", "terms", "query_text",
            "queryText", "search_term", "searchTerm", "name", "filename", "file_name",
        )
        private val QUERY_HINTS = listOf("query", "search", "text", "term", "keyword")
        private val LIMIT_NAMES = listOf(
            "limit", "max_results", "maxResults", "top", "page_size", "pageSize", "count", "num_results", "numResults", "max",
        )
        private val LOCATOR_PARAMETERS = listOf(
            "path", "path_display", "file_path", "filePath", "id", "file_id", "fileId", "document_id", "documentId", "item_id", "itemId",
            "resource_id", "resourceId", "uri", "url",
        )
        private val SEARCH_HINTS = listOf("file", "doc", "drive", "content")
        private val DOWNLOAD_HINTS = listOf(
            "file", "document", "doc", "content", "download", "fetch", "read", "link", "item", "attachment", "blob", "object",
        )
        private val DOWNLOAD_EXCLUDED = listOf(
            "metadata", "permission", "list", "search", "upload", "create", "delete", "move", "copy", "share", "markdown", "transcript",
            "revision", "restore", "folder", "quota", "whoami", "job", "comment", "version",
        )
        private val DOWNLOAD_PRIORITY = listOf("download", "link", "content", "fetch", "read", "get")

        internal fun searchTool(tools: List<McpTool>): McpTool? {
            val candidates = tools.filter { it.name.contains("search", ignoreCase = true) && queryParameter(it) != null }
            return candidates.firstOrNull { it.name.equals("search", ignoreCase = true) }
                ?: candidates.firstOrNull { tool -> SEARCH_HINTS.any { tool.name.contains(it, ignoreCase = true) } }
                ?: candidates.firstOrNull()
        }

        internal fun queryParameter(tool: McpTool): String? {
            val strings = tool.properties().filterValues { it.isType("string") }.keys
            val required = tool.required()
            return QUERY_NAMES.firstNotNullOfOrNull { wanted -> strings.firstOrNull { it.equals(wanted, ignoreCase = true) } }
                ?: strings.firstOrNull { name -> name in required && QUERY_HINTS.any { name.contains(it, ignoreCase = true) } }
                ?: strings.singleOrNull { it in required }
        }

        internal fun limitParameter(tool: McpTool): String? {
            val numbers = tool.properties().filterValues { it.isType("integer") || it.isType("number") }.keys
            return LIMIT_NAMES.firstNotNullOfOrNull { wanted -> numbers.firstOrNull { it.equals(wanted, ignoreCase = true) } }
        }

        internal fun downloadTool(tools: List<McpTool>): McpTool? {
            val candidates = tools.filter { tool ->
                DOWNLOAD_EXCLUDED.none { tool.name.contains(it, ignoreCase = true) } &&
                    DOWNLOAD_HINTS.any { tool.name.contains(it, ignoreCase = true) } &&
                    locatorParameter(tool) != null
            }
            return DOWNLOAD_PRIORITY.firstNotNullOfOrNull { word -> candidates.firstOrNull { it.name.contains(word, ignoreCase = true) } }
        }

        internal fun locatorParameter(tool: McpTool): String? {
            val strings = tool.properties().filterValues { it.isType("string") }.keys
            return LOCATOR_PARAMETERS.firstNotNullOfOrNull { wanted -> strings.firstOrNull { it.equals(wanted, ignoreCase = true) } }
        }

        internal fun locatorKey(parameter: String): String = when {
            parameter.contains("path", ignoreCase = true) -> LOCATOR_PATH
            parameter.equals("uri", ignoreCase = true) -> LOCATOR_URI
            parameter.equals("url", ignoreCase = true) -> LOCATOR_URL
            else -> LOCATOR_ID
        }

        internal fun filesFrom(server: McpServer, result: McpToolResult): List<CloudFile> {
            val files = mutableListOf<CloudFile>()
            result.structuredContent?.let { files += filesFromJson(server, it, 0) }
            for (item in result.content) {
                when (item) {
                    is McpContent.ResourceLink -> fileFromLink(server, item)?.let(files::add)
                    is McpContent.Text -> parseJson(item.text)?.let { files += filesFromJson(server, it, 0) }
                    is McpContent.Resource -> item.resource.uri?.let { uri ->
                        val name = uri.substringAfterLast('/').substringBefore('?')
                        val mime = CloudSearchService.supportedMimeType(item.resource.mimeType, name) ?: return@let
                        files += CloudFile(server, uri, null, name, mime, null, server.name, null, mapOf(LOCATOR_URI to uri))
                    }
                    is McpContent.Image -> Unit
                }
            }
            return files
        }

        private fun filesFromJson(server: McpServer, element: JsonElement, depth: Int): List<CloudFile> {
            if (depth > MAX_DEPTH) return emptyList()
            return when (element) {
                is JsonArray -> element.flatMap { filesFromJson(server, it, depth + 1) }
                is JsonObject -> fileFromObject(server, element)?.let { listOf(it) } ?: run {
                    val lists = LIST_KEYS.mapNotNull { element[it] as? JsonArray }.ifEmpty { element.values.filterIsInstance<JsonArray>() }
                    val nested = NESTED_KEYS.mapNotNull { element[it] as? JsonObject }
                    (lists + nested).flatMap { filesFromJson(server, it, depth + 1) }
                }
                else -> emptyList()
            }
        }

        private fun fileFromObject(server: McpServer, obj: JsonObject): CloudFile? {
            val name = obj.first(NAME_KEYS) ?: return null
            val uri = obj.string("uri")?.takeIf { it.contains("://") }
            val url = obj.first(URL_KEYS)?.takeIf { it.startsWith("http", ignoreCase = true) }
            val mime = CloudSearchService.supportedMimeType(obj.first(MIME_KEYS), name)
                ?: uri?.let { CloudSearchService.supportedMimeType(null, it.substringBefore('?')) }
                ?: return null
            val id = obj.first(ID_KEYS)
            val path = obj.first(PATH_KEYS)
            val locators = buildMap {
                id?.let { put(LOCATOR_ID, it) }
                path?.let { put(LOCATOR_PATH, it) }
                uri?.let { put(LOCATOR_URI, it) }
                url?.let { put(LOCATOR_URL, it) }
            }
            val key = id ?: uri ?: url ?: path ?: return null
            val folder = path?.substringBeforeLast('/', "")?.trim('/')?.ifBlank { null }
            val context = folder?.let { "${server.name}/$it" } ?: obj.first(CONTEXT_KEYS) ?: server.name
            return CloudFile(server, key, null, name, mime, obj.firstLong(SIZE_KEYS), context, obj.first(MODIFIED_KEYS), locators)
        }

        private fun fileFromLink(server: McpServer, link: McpContent.ResourceLink): CloudFile? {
            val name = link.name?.takeIf { it.isNotBlank() } ?: link.uri.substringAfterLast('/').substringBefore('?')
            val mime = CloudSearchService.supportedMimeType(link.mimeType, name) ?: return null
            return CloudFile(server, link.uri, null, name, mime, link.size, link.description ?: server.name, null, mapOf(LOCATOR_URI to link.uri))
        }

        private fun linkIn(text: String): String? {
            val trimmed = text.trim()
            if (trimmed.startsWith("https://", ignoreCase = true) && trimmed.none { it.isWhitespace() }) return trimmed
            return parseJson(trimmed)?.let { element -> (element as? JsonObject)?.let(::linkIn) }
        }

        private fun linkIn(obj: JsonObject): String? =
            obj.first(LINK_KEYS)?.takeIf { it.startsWith("https://", ignoreCase = true) }
                ?: NESTED_KEYS.firstNotNullOfOrNull { key -> (obj[key] as? JsonObject)?.let(::linkIn) }

        internal fun decodeBase64(data: String): ByteArray {
            val cleaned = data.filterNot { it.isWhitespace() }
            return runCatching { Base64.Default.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL).decode(cleaned) }
                .recoverCatching { Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL).decode(cleaned) }
                .getOrElse { throw CloudException("Couldn't decode the file", it) }
        }

        private fun parseJson(text: String): JsonElement? {
            val trimmed = text.trim()
            if (!trimmed.startsWith('{') && !trimmed.startsWith('[')) return null
            return runCatching { json.parseToJsonElement(trimmed) }.getOrNull()
        }

        private fun JsonObject.first(keys: List<String>): String? = keys.firstNotNullOfOrNull { string(it) }

        private fun JsonObject.firstLong(keys: List<String>): Long? = keys.firstNotNullOfOrNull { long(it) }

        private fun McpTool.properties(): Map<String, JsonObject> = (inputSchema?.get("properties") as? JsonObject)
            ?.mapNotNull { (key, value) -> (value as? JsonObject)?.let { key to it } }
            ?.toMap()
            .orEmpty()

        private fun McpTool.required(): Set<String> = inputSchema?.stringList("required")?.toSet().orEmpty()

        private fun JsonObject.isType(type: String): Boolean = when (val declared = this["type"]) {
            is JsonPrimitive -> declared.contentOrNull == type
            is JsonArray -> declared.any { (it as? JsonPrimitive)?.contentOrNull == type }
            null -> type == "string" && this["properties"] == null && this["items"] == null && this["enum"] == null
            else -> false
        }
    }
}
