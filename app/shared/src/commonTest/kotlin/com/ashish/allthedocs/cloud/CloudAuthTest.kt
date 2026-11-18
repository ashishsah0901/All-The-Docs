package com.ashish.allthedocs.cloud

import com.ashish.allthedocs.data.ApiClient
import com.ashish.allthedocs.platform.SecureFileStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CloudAuthTest {
    private val config = OAuthConfig(
        authorizeUrl = "https://login.example.com/authorize",
        tokenUrl = "https://login.example.com/token",
        clientId = "client-123",
        redirectUri = "com.ashish.allthedocs://oauth/microsoft",
        scopes = listOf("offline_access", "Mail.Read"),
    )
    private val tokenRequests = mutableListOf<Map<String, String>>()
    private var tokenStatus = HttpStatusCode.OK
    private var expiresIn = 3600

    private val httpClient = ApiClient.createHttpClient(
        MockEngine { request ->
            tokenRequests += formOf(request)
            respond(
                """{"access_token":"access-${tokenRequests.size}","refresh_token":"refresh-${tokenRequests.size}","expires_in":$expiresIn}""",
                tokenStatus,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        },
    )

    private fun formOf(request: HttpRequestData): Map<String, String> {
        val text = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        return parseQueryString(text).entries().associate { it.key to it.value.single() }
    }

    @Test
    fun codeChallengeMatchesRfc7636Vector() =
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", PkceOAuthClient.codeChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))

    @Test
    fun authorizationUrlCarriesPkceParameters() {
        val request = PkceOAuthClient(httpClient, config).newRequest()
        val parameters = Url(request.url).parameters
        assertEquals("client-123", parameters["client_id"])
        assertEquals("code", parameters["response_type"])
        assertEquals("S256", parameters["code_challenge_method"])
        assertEquals(PkceOAuthClient.codeChallenge(request.codeVerifier), parameters["code_challenge"])
        assertEquals(request.state, parameters["state"])
        assertEquals("offline_access Mail.Read", parameters["scope"])
        assertTrue(request.codeVerifier.length >= 43)
    }

    @Test
    fun exchangeSendsVerifierAndReturnsTokens() = runTest {
        val client = PkceOAuthClient(httpClient, config)
        val request = client.newRequest()

        val tokens = client.exchange("${config.redirectUri}?code=abc&state=${request.state}", request)

        assertEquals("access-1", tokens.accessToken)
        assertEquals("refresh-1", tokens.refreshToken)
        val form = tokenRequests.single()
        assertEquals("authorization_code", form["grant_type"])
        assertEquals("abc", form["code"])
        assertEquals(request.codeVerifier, form["code_verifier"])
    }

    @Test
    fun exchangeRejectsStateMismatchAndProviderErrors() = runTest {
        val client = PkceOAuthClient(httpClient, config)
        val request = client.newRequest()
        assertFailsWith<CloudAuthException> { client.exchange("${config.redirectUri}?code=abc&state=forged", request) }
        assertFailsWith<CloudAuthException> {
            client.exchange("${config.redirectUri}?error=access_denied&state=${request.state}", request)
        }
        assertTrue(tokenRequests.isEmpty())
    }

    @Test
    fun managerConnectsStoresEncryptedTokensAndRefreshesWhenExpired() = runTest {
        expiresIn = 0
        val store = MemoryStore()
        val manager = CloudAuthManager(httpClient, store, FakePlatform(), googleConfig = null, microsoftConfig = config)
        manager.refreshStatus()
        assertEquals(AccountStatus.DISCONNECTED, manager.status.value[CloudAccount.MICROSOFT])
        assertEquals(AccountStatus.NOT_CONFIGURED, manager.status.value[CloudAccount.GOOGLE])

        manager.connect(CloudAccount.MICROSOFT)

        assertEquals(AccountStatus.CONNECTED, manager.status.value[CloudAccount.MICROSOFT])
        assertTrue(store.files.containsKey("oauth-microsoft.bin"))
        assertEquals("access-2", manager.accessToken(CloudAccount.MICROSOFT))
        assertEquals("refresh_token", tokenRequests.last()["grant_type"])
        assertEquals("refresh-1", tokenRequests.last()["refresh_token"])
    }

    @Test
    fun revokedRefreshTokenDisconnectsAccount() = runTest {
        expiresIn = 0
        val manager = CloudAuthManager(httpClient, MemoryStore(), FakePlatform(), googleConfig = null, microsoftConfig = config)
        manager.connect(CloudAccount.MICROSOFT)
        tokenStatus = HttpStatusCode.BadRequest

        assertFailsWith<CloudAuthException> { manager.accessToken(CloudAccount.MICROSOFT) }
        assertEquals(AccountStatus.DISCONNECTED, manager.status.value[CloudAccount.MICROSOFT])
        assertFailsWith<CloudAuthException> { manager.accessToken(CloudAccount.MICROSOFT) }
    }

    @Test
    fun nativeGoogleUsesPlatformTokens() = runTest {
        val platform = FakePlatform(native = true)
        val manager = CloudAuthManager(httpClient, MemoryStore(), platform, googleConfig = null, microsoftConfig = null)
        manager.refreshStatus()
        assertEquals(AccountStatus.DISCONNECTED, manager.status.value[CloudAccount.GOOGLE])

        manager.connect(CloudAccount.GOOGLE)

        assertEquals("google-token", manager.accessToken(CloudAccount.GOOGLE))
        assertEquals(listOf(true, false), platform.googleCalls)
        assertTrue(tokenRequests.isEmpty())
    }

    @Test
    fun disconnectRemovesStoredTokens() = runTest {
        val store = MemoryStore()
        val manager = CloudAuthManager(httpClient, store, FakePlatform(), googleConfig = null, microsoftConfig = config)
        manager.connect(CloudAccount.MICROSOFT)

        manager.disconnect(CloudAccount.MICROSOFT)

        assertTrue(store.files.isEmpty())
        assertEquals(AccountStatus.DISCONNECTED, manager.status.value[CloudAccount.MICROSOFT])
        assertFailsWith<CloudAuthException> { manager.accessToken(CloudAccount.MICROSOFT) }
    }
}

private class FakePlatform(private val native: Boolean = false) : PlatformCloudAuth {
    val googleCalls = mutableListOf<Boolean>()
    override val handlesGoogleNatively: Boolean = native
    override val browser: BrowserAuthenticator = object : BrowserAuthenticator {
        override suspend fun authenticate(url: String, redirectUri: String): String =
            "$redirectUri?code=granted&state=${Url(url).parameters["state"]}"
    }

    override suspend fun googleAccessToken(interactive: Boolean): String {
        googleCalls += interactive
        return "google-token"
    }

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
