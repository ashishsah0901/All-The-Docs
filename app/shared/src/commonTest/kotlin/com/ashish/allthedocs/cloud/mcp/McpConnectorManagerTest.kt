package com.ashish.allthedocs.cloud.mcp

import com.ashish.allthedocs.cloud.AccountStatus
import com.ashish.allthedocs.cloud.BrowserAuthenticator
import com.ashish.allthedocs.cloud.CloudAuthException
import com.ashish.allthedocs.cloud.CloudException
import com.ashish.allthedocs.cloud.PlatformCloudAuth
import com.ashish.allthedocs.data.ApiClient
import com.ashish.allthedocs.platform.SecureFileStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpConnectorManagerTest {
    private val requests = mutableListOf<HttpRequestData>()
    private var registrationBody: JsonObject? = null
    private val tokenForms = mutableListOf<Map<String, String>>()
    private var tokenStatus = HttpStatusCode.OK
    private var expiresIn = 3600
    private var serverStatus = HttpStatusCode.Unauthorized
    private var challenge: String? =
        "Bearer realm=\"mcp\", resource_metadata=\"https://mcp.example.com/.well-known/oauth-protected-resource/mcp\", scope=\"files:read\""
    private var metadataAvailable = true
    private var registrationAvailable = true

    private val httpClient = ApiClient.createHttpClient(
        MockEngine { request ->
            requests += request
            val host = request.url.host
            val path = request.url.encodedPath
            when {
                host == "mcp.example.com" && path == "/mcp" && request.method == HttpMethod.Post ->
                    if (serverStatus == HttpStatusCode.OK) {
                        json("""{"jsonrpc":"2.0","id":0,"result":{"protocolVersion":"2025-06-18","capabilities":{},"serverInfo":{"name":"t","version":"1"}}}""")
                    } else {
                        respond("", serverStatus, challenge?.let { headersOf(HttpHeaders.WWWAuthenticate, it) } ?: headersOf())
                    }
                host == "mcp.example.com" && path == "/.well-known/oauth-protected-resource/mcp" && metadataAvailable ->
                    json("""{"resource":"https://mcp.example.com/mcp","authorization_servers":["https://auth.example.com"],"scopes_supported":["files:read","files:write"]}""")
                host == "auth.example.com" && path == "/.well-known/oauth-authorization-server" && metadataAvailable ->
                    json(
                        """{"issuer":"https://auth.example.com","authorization_endpoint":"https://auth.example.com/authorize",""" +
                            """"token_endpoint":"https://auth.example.com/token"""" +
                            (if (registrationAvailable) ""","registration_endpoint":"https://auth.example.com/register"}""" else "}"),
                    )
                path == "/register" -> {
                    registrationBody = Json.parseToJsonElement(request.bodyText()).jsonObject
                    json("""{"client_id":"dyn-1"}""", HttpStatusCode.Created)
                }
                path == "/token" -> {
                    tokenForms += parseQueryString(request.bodyText()).entries().associate { it.key to it.value.single() }
                    json(
                        """{"access_token":"access-${tokenForms.size}","refresh_token":"refresh-${tokenForms.size}","expires_in":$expiresIn}""",
                        tokenStatus,
                    )
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        },
    )
    private val store = MemoryStore()
    private val platform = FakePlatform()
    private val manager = McpConnectorManager(httpClient, store, platform)

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun HttpRequestData.bodyText() = (body as OutgoingContent.ByteArrayContent).bytes().decodeToString()

    @Test
    fun addDiscoversRegistersAndSignsIn() = runTest {
        val server = manager.add("Example", " https://mcp.example.com/mcp/ ")

        assertEquals("https://mcp.example.com/mcp", server.url)
        assertEquals("Example", server.name)
        val registration = registrationBody!!
        assertEquals("com.ashish.allthedocs://oauth/mcp", registration["redirect_uris"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("none", registration["token_endpoint_auth_method"]!!.jsonPrimitive.content)
        val authorize = Url(platform.authorizeUrls.single())
        assertEquals("auth.example.com", authorize.host)
        assertEquals("/authorize", authorize.encodedPath)
        assertEquals("dyn-1", authorize.parameters["client_id"])
        assertEquals("files:read", authorize.parameters["scope"])
        assertEquals("https://mcp.example.com/mcp", authorize.parameters["resource"])
        assertEquals("com.ashish.allthedocs://oauth/mcp", authorize.parameters["redirect_uri"])
        assertEquals("S256", authorize.parameters["code_challenge_method"])
        val form = tokenForms.single()
        assertEquals("authorization_code", form["grant_type"])
        assertEquals("https://mcp.example.com/mcp", form["resource"])
        assertNotNull(form["code_verifier"])
        assertNull(form["client_secret"])
        assertEquals(AccountStatus.CONNECTED, manager.servers.value.single().status)
        assertEquals("access-1", manager.accessToken(server))
        assertTrue(store.files.containsKey("mcp-servers.bin"))
        assertTrue(store.files.keys.any { it.startsWith("oauth-mcp-") })

        val reloaded = McpConnectorManager(httpClient, store, platform)
        reloaded.refreshStatus()
        assertEquals(listOf(server), reloaded.servers.value.map { it.server })
        assertEquals("access-1", reloaded.accessToken(server))
    }

    @Test
    fun addFallsBackToPresetEndpointsAndSuppliedClient() = runTest {
        challenge = null
        metadataAvailable = false
        val preset = McpPreset(
            name = "Box",
            url = "https://mcp.example.com/mcp",
            hint = "hint",
            authorizeUrl = "https://preset.example.com/authorize",
            tokenUrl = "https://preset.example.com/token",
            extraParameters = mapOf("token_access_type" to "offline"),
            needsClientId = true,
            needsClientSecret = true,
        )

        val server = manager.add("Box", "https://mcp.example.com/mcp", clientId = " my-app ", clientSecret = "shh", preset = preset)

        assertNull(registrationBody)
        val authorize = Url(platform.authorizeUrls.single())
        assertEquals("preset.example.com", authorize.host)
        assertEquals("my-app", authorize.parameters["client_id"])
        assertEquals("offline", authorize.parameters["token_access_type"])
        assertNull(authorize.parameters["scope"])
        assertEquals("shh", tokenForms.single()["client_secret"])
        assertEquals("preset.example.com", requests.last { it.url.encodedPath == "/token" }.url.host)
        assertEquals(AccountStatus.CONNECTED, manager.servers.value.single { it.server == server }.status)
    }

    @Test
    fun addWithoutClientIdOrRegistrationFails() = runTest {
        registrationAvailable = false

        val error = assertFailsWith<CloudAuthException> { manager.add("Example", "https://mcp.example.com/mcp") }

        assertTrue(error.message!!.contains("com.ashish.allthedocs://oauth/mcp"))
        assertTrue(store.files.isEmpty())
        assertTrue(manager.servers.value.isEmpty())
        assertTrue(platform.authorizeUrls.isEmpty())
    }

    @Test
    fun serverWithoutAuthConnectsDirectly() = runTest {
        serverStatus = HttpStatusCode.OK

        val server = manager.add("Open", "https://mcp.example.com/mcp")

        assertEquals(AccountStatus.CONNECTED, manager.servers.value.single().status)
        assertNull(manager.accessToken(server))
        assertTrue(tokenForms.isEmpty())
        assertTrue(platform.authorizeUrls.isEmpty())
        assertEquals(setOf("mcp-servers.bin"), store.files.keys)
    }

    @Test
    fun refreshesExpiredTokensAndDisconnectsWhenRevoked() = runTest {
        expiresIn = 0
        val server = manager.add("Example", "https://mcp.example.com/mcp")

        assertEquals("access-2", manager.accessToken(server))
        val refresh = tokenForms.last()
        assertEquals("refresh_token", refresh["grant_type"])
        assertEquals("refresh-1", refresh["refresh_token"])
        assertEquals("https://mcp.example.com/mcp", refresh["resource"])

        tokenStatus = HttpStatusCode.BadRequest
        assertFailsWith<CloudAuthException> { manager.accessToken(server) }
        assertEquals(AccountStatus.DISCONNECTED, manager.servers.value.single().status)

        tokenStatus = HttpStatusCode.OK
        expiresIn = 3600
        manager.connect(server)
        assertEquals(AccountStatus.CONNECTED, manager.servers.value.single().status)
        assertEquals(2, platform.authorizeUrls.size)
        assertEquals("access-4", manager.accessToken(server))
    }

    @Test
    fun disconnectKeepsServerAndRemoveDeletesIt() = runTest {
        val server = manager.add("Example", "https://mcp.example.com/mcp")

        manager.disconnect(server)
        assertEquals(AccountStatus.DISCONNECTED, manager.servers.value.single().status)
        assertFailsWith<CloudAuthException> { manager.accessToken(server) }

        manager.remove(server)
        assertTrue(manager.servers.value.isEmpty())
        assertTrue(store.files.keys.none { it.startsWith("oauth-mcp-") })
        assertFailsWith<CloudAuthException> { manager.accessToken(server) }
    }

    @Test
    fun rejectsDuplicateAndMalformedUrls() = runTest {
        manager.add("Example", "https://mcp.example.com/mcp")

        assertFailsWith<CloudException> { manager.add("Again", "https://mcp.example.com/mcp/") }
        assertFailsWith<CloudException> { manager.add("Bad", "mcp.example.com/mcp") }
        assertFailsWith<CloudException> { manager.add("Plain", "http://mcp.example.com/mcp") }
        assertFailsWith<CloudException> { manager.add(" ", "https://mcp.example.com/other") }
        assertEquals("http://localhost:3000/mcp", McpConnectorManager.canonicalUrl("http://localhost:3000/mcp/#x"))
        assertEquals(1, manager.servers.value.size)
    }

    @Test
    fun parsesChallengeHeader() {
        val parsed = McpAuthDiscovery.parseChallenge(
            "Bearer realm=\"mcp\", resource_metadata=\"https://a.example.com/.well-known/oauth-protected-resource\", " +
                "scope=\"files:read files:write\", error=invalid_token",
        )

        assertEquals("https://a.example.com/.well-known/oauth-protected-resource", parsed["resource_metadata"])
        assertEquals("files:read files:write", parsed["scope"])
        assertEquals("invalid_token", parsed["error"])
        assertTrue(McpAuthDiscovery.parseChallenge(null).isEmpty())
    }

    @Test
    fun wellKnownUrlsArePathAware() {
        assertEquals(
            listOf(
                "https://a.example.com/.well-known/oauth-authorization-server/tenant",
                "https://a.example.com/.well-known/oauth-authorization-server",
            ),
            McpAuthDiscovery.wellKnown("https://a.example.com/tenant/", "oauth-authorization-server"),
        )
        assertEquals(listOf("https://a.example.com:8443/.well-known/x"), McpAuthDiscovery.wellKnown("https://a.example.com:8443", "x"))
    }
}

private class FakePlatform : PlatformCloudAuth {
    val authorizeUrls = mutableListOf<String>()
    override val handlesGoogleNatively: Boolean = false
    override val browser: BrowserAuthenticator = object : BrowserAuthenticator {
        override suspend fun authenticate(url: String, redirectUri: String): String {
            authorizeUrls += url
            return "$redirectUri?code=granted&state=${Url(url).parameters["state"]}"
        }
    }

    override suspend fun googleAccessToken(interactive: Boolean): String = error("unused")

    override suspend fun googleSignOut() = Unit
}

private class MemoryStore : SecureFileStore {
    val files = mutableMapOf<String, ByteArray>()

    override suspend fun write(name: String, bytes: ByteArray) {
        files[name] = bytes
    }

    override suspend fun read(name: String): ByteArray = files[name] ?: error("missing")

    override suspend fun delete(name: String) {
        files.remove(name)
    }
}
