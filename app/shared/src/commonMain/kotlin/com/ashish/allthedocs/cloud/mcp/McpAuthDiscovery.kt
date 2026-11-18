package com.ashish.allthedocs.cloud.mcp

import com.ashish.allthedocs.cloud.CloudAuthException
import com.ashish.allthedocs.cloud.CloudException
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class McpAuthDiscovery(private val httpClient: HttpClient) {
    class Probe(val authRequired: Boolean, val resourceMetadataUrl: String?, val scope: String?)

    class ProtectedResource(val authorizationServers: List<String>, val scopesSupported: List<String>)

    class AuthorizationServer(
        val authorizationEndpoint: String,
        val tokenEndpoint: String,
        val registrationEndpoint: String?,
        val scopesSupported: List<String>,
    )

    class Registration(val clientId: String, val clientSecret: String?)

    suspend fun probe(serverUrl: String): Probe {
        val response = attempt(serverUrl) {
            httpClient.post(serverUrl) {
                header(HttpHeaders.Accept, McpClient.ACCEPT)
                header(McpClient.PROTOCOL_HEADER, McpClient.PROTOCOL_VERSION)
                setBody(TextContent(INITIALIZE_PROBE, ContentType.Application.Json))
            }
        }
        return when {
            response.status.isSuccess() -> Probe(authRequired = false, resourceMetadataUrl = null, scope = null)
            response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden -> {
                val challenge = parseChallenge(response.headers[HttpHeaders.WWWAuthenticate])
                Probe(authRequired = true, resourceMetadataUrl = challenge["resource_metadata"], scope = challenge["scope"])
            }
            else -> throw CloudException("${Url(serverUrl).host} didn't answer like an MCP server (${response.status.value})")
        }
    }

    suspend fun protectedResource(serverUrl: String, metadataUrl: String?): ProtectedResource? {
        val candidates = listOfNotNull(metadataUrl) + wellKnown(serverUrl, "oauth-protected-resource")
        for (candidate in candidates.distinct()) {
            val metadata = fetchJson(candidate) ?: continue
            val servers = metadata.stringList("authorization_servers")
            if (servers.isNotEmpty()) return ProtectedResource(servers, metadata.stringList("scopes_supported"))
        }
        return null
    }

    suspend fun authorizationServer(issuer: String): AuthorizationServer? {
        val candidates = buildList {
            addAll(wellKnown(issuer, "oauth-authorization-server"))
            addAll(wellKnown(issuer, "openid-configuration"))
            if (Url(issuer).encodedPath.trimEnd('/').isNotEmpty()) add(issuer.trimEnd('/') + "/.well-known/openid-configuration")
        }
        for (candidate in candidates.distinct()) {
            val metadata = fetchJson(candidate) ?: continue
            val authorize = metadata.string("authorization_endpoint") ?: continue
            val token = metadata.string("token_endpoint") ?: continue
            return AuthorizationServer(authorize, token, metadata.string("registration_endpoint"), metadata.stringList("scopes_supported"))
        }
        return null
    }

    suspend fun register(registrationEndpoint: String, redirectUri: String): Registration {
        val body = buildJsonObject {
            put("client_name", CLIENT_NAME)
            put("redirect_uris", JsonArray(listOf(JsonPrimitive(redirectUri))))
            put("grant_types", JsonArray(listOf("authorization_code", "refresh_token").map(::JsonPrimitive)))
            put("response_types", JsonArray(listOf(JsonPrimitive("code"))))
            put("token_endpoint_auth_method", "none")
            put("application_type", "native")
        }
        val response = attempt(registrationEndpoint) {
            httpClient.post(registrationEndpoint) {
                header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                setBody(TextContent(body.toString(), ContentType.Application.Json))
            }
        }
        if (!response.status.isSuccess()) {
            throw CloudAuthException("The sign-in service refused to register this app (${response.status.value}). Enter a client ID instead.")
        }
        val result = parseJson(response.bodyAsText()) ?: throw CloudException("Unexpected response from the sign-in service")
        val clientId = result.string("client_id") ?: throw CloudException("The sign-in service didn't return a client ID")
        return Registration(clientId, result.string("client_secret"))
    }

    private suspend fun fetchJson(url: String): JsonObject? {
        val response = try {
            httpClient.get(url) { header(HttpHeaders.Accept, ContentType.Application.Json.toString()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        if (!response.status.isSuccess()) return null
        return parseJson(response.bodyAsText())
    }

    private suspend fun attempt(url: String, block: suspend () -> HttpResponse): HttpResponse = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw CloudException("Couldn't reach ${Url(url).host}", e)
    }

    companion object {
        private const val CLIENT_NAME = "AllTheDocs"
        private val json = Json { ignoreUnknownKeys = true }
        private val CHALLENGE_PARAMETER = Regex("""(\w+)\s*=\s*(?:"([^"]*)"|([^,\s]+))""")
        private val INITIALIZE_PROBE = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 0)
            put("method", "initialize")
            put(
                "params",
                buildJsonObject {
                    put("protocolVersion", McpClient.PROTOCOL_VERSION)
                    put("capabilities", buildJsonObject {})
                    put(
                        "clientInfo",
                        buildJsonObject {
                            put("name", CLIENT_NAME)
                            put("version", "1.0")
                        },
                    )
                },
            )
        }.toString()

        internal fun parseChallenge(header: String?): Map<String, String> = header?.let { value ->
            CHALLENGE_PARAMETER.findAll(value).associate { match ->
                match.groupValues[1].lowercase() to match.groupValues[2].ifEmpty { match.groupValues[3] }
            }
        }.orEmpty()

        internal fun origin(url: Url): String = buildString {
            append(url.protocol.name)
            append("://")
            append(url.host)
            if (url.specifiedPort != 0 && url.specifiedPort != url.protocol.defaultPort) append(':').append(url.specifiedPort)
        }

        internal fun wellKnown(baseUrl: String, suffix: String): List<String> {
            val url = Url(baseUrl)
            val origin = origin(url)
            val path = url.encodedPath.trimEnd('/')
            return buildList {
                if (path.isNotEmpty()) add("$origin/.well-known/$suffix$path")
                add("$origin/.well-known/$suffix")
            }
        }

        private fun parseJson(text: String): JsonObject? = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
    }
}
