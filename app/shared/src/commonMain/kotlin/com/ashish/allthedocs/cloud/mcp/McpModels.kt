package com.ashish.allthedocs.cloud.mcp

import com.ashish.allthedocs.cloud.AccountStatus
import com.ashish.allthedocs.cloud.CloudException
import com.ashish.allthedocs.cloud.McpServer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

@Serializable
data class McpServerRecord(
    val id: String,
    val name: String,
    val url: String,
    val authRequired: Boolean = true,
    val clientId: String? = null,
    val clientSecret: String? = null,
    val authorizeUrl: String? = null,
    val tokenUrl: String? = null,
    val scopes: List<String> = emptyList(),
    val extraParameters: Map<String, String> = emptyMap(),
) {
    val server: McpServer get() = McpServer(id, name, url)
}

data class McpServerState(val server: McpServer, val status: AccountStatus)

data class McpPreset(
    val name: String,
    val url: String,
    val hint: String,
    val authorizeUrl: String? = null,
    val tokenUrl: String? = null,
    val scopes: List<String> = emptyList(),
    val extraParameters: Map<String, String> = emptyMap(),
    val needsClientId: Boolean = false,
    val needsClientSecret: Boolean = false,
)

object McpPresets {
    val DROPBOX = McpPreset(
        name = "Dropbox",
        url = "https://mcp.dropbox.com/mcp",
        hint = "Dropbox registers only trusted apps on its own. If Connect fails, create a Dropbox app " +
            "(scoped access, Full Dropbox) with the redirect URI below and enter its App key as the client ID.",
        authorizeUrl = "https://www.dropbox.com/oauth2/authorize",
        tokenUrl = "https://api.dropboxapi.com/oauth2/token",
        extraParameters = mapOf("token_access_type" to "offline"),
        needsClientId = true,
    )
    val BOX = McpPreset(
        name = "Box",
        url = "https://mcp.box.com",
        hint = "In the Box Admin Console open Integrations → Box MCP server → Configure, add the redirect URI below, " +
            "and enter the client ID and client secret it shows.",
        authorizeUrl = "https://account.box.com/api/oauth2/authorize",
        tokenUrl = "https://api.box.com/oauth2/token",
        needsClientId = true,
        needsClientSecret = true,
    )
    val all: List<McpPreset> = listOf(DROPBOX, BOX)
}

fun interface McpTokenSource {
    suspend fun accessToken(server: McpServer): String?
}

class McpTool(val name: String, val description: String?, val inputSchema: JsonObject?)

class McpResourceContent(val uri: String?, val mimeType: String?, val text: String?, val blob: String?)

sealed interface McpContent {
    class Text(val text: String) : McpContent

    class Image(val data: String, val mimeType: String?) : McpContent

    class ResourceLink(
        val uri: String,
        val name: String?,
        val mimeType: String?,
        val size: Long?,
        val description: String?,
    ) : McpContent

    class Resource(val resource: McpResourceContent) : McpContent
}

class McpToolResult(val content: List<McpContent>, val structuredContent: JsonObject?, val isError: Boolean)

class McpRpcException(message: String, val code: Int?) : CloudException(message)

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

internal fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }

internal fun JsonObject.stringList(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

internal fun JsonObject.array(key: String): List<JsonObject> = (this[key] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
