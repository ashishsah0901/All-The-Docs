package com.ashish.allthedocs.cloud

import com.ashish.allthedocs.config.AppSecrets
import com.ashish.allthedocs.platform.SecureFileStore
import com.ashish.allthedocs.platform.secureRandomBytes
import com.ashish.allthedocs.platform.sha256
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

interface BrowserAuthenticator {
    suspend fun authenticate(url: String, redirectUri: String): String
}

interface PlatformCloudAuth {
    val browser: BrowserAuthenticator
    val handlesGoogleNatively: Boolean
    suspend fun googleAccessToken(interactive: Boolean): String
    suspend fun googleSignOut()
}

data class OAuthConfig(
    val authorizeUrl: String,
    val tokenUrl: String,
    val clientId: String,
    val redirectUri: String,
    val scopes: List<String>,
    val extraParameters: Map<String, String> = emptyMap(),
    val clientSecret: String? = null,
    val resource: String? = null,
)

object OAuthConfigs {
    const val MICROSOFT_REDIRECT_URI = "com.ashish.allthedocs://oauth/microsoft"
    const val MCP_REDIRECT_URI = "com.ashish.allthedocs://oauth/mcp"
    val GOOGLE_SCOPES = listOf(
        "https://www.googleapis.com/auth/gmail.readonly",
        "https://www.googleapis.com/auth/drive.readonly",
    )

    fun microsoft(clientId: String = AppSecrets.MICROSOFT_CLIENT_ID): OAuthConfig? = clientId.takeIf { it.isNotBlank() }?.let {
        OAuthConfig(
            authorizeUrl = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
            tokenUrl = "https://login.microsoftonline.com/common/oauth2/v2.0/token",
            clientId = it,
            redirectUri = MICROSOFT_REDIRECT_URI,
            scopes = listOf("offline_access", "User.Read", "Mail.Read", "Files.Read"),
            extraParameters = mapOf("prompt" to "select_account"),
        )
    }

    fun googleBrowser(clientId: String = AppSecrets.GOOGLE_IOS_CLIENT_ID): OAuthConfig? = clientId.takeIf { it.isNotBlank() }?.let {
        val reversed = "com.googleusercontent.apps." + it.removeSuffix(".apps.googleusercontent.com")
        OAuthConfig(
            authorizeUrl = "https://accounts.google.com/o/oauth2/v2/auth",
            tokenUrl = "https://oauth2.googleapis.com/token",
            clientId = it,
            redirectUri = "$reversed:/oauth2redirect",
            scopes = GOOGLE_SCOPES,
            extraParameters = mapOf("access_type" to "offline", "prompt" to "consent"),
        )
    }
}

@Serializable
data class OAuthTokens(
    val accessToken: String = "",
    val refreshToken: String? = null,
    val expiresAtEpochMs: Long = 0,
    val platformManaged: Boolean = false,
)

class AuthRequest(val url: String, val state: String, val codeVerifier: String)

class PkceOAuthClient(private val httpClient: HttpClient, private val config: OAuthConfig) {
    fun newRequest(): AuthRequest {
        val verifier = base64Url(secureRandomBytes(32))
        val state = base64Url(secureRandomBytes(16))
        val url = URLBuilder(config.authorizeUrl).apply {
            parameters.append("client_id", config.clientId)
            parameters.append("response_type", "code")
            parameters.append("redirect_uri", config.redirectUri)
            if (config.scopes.isNotEmpty()) parameters.append("scope", config.scopes.joinToString(" "))
            parameters.append("state", state)
            parameters.append("code_challenge", codeChallenge(verifier))
            parameters.append("code_challenge_method", "S256")
            config.extraParameters.forEach { (key, value) -> parameters.append(key, value) }
            config.resource?.let { parameters.append("resource", it) }
        }.buildString()
        return AuthRequest(url, state, verifier)
    }

    suspend fun exchange(callbackUrl: String, request: AuthRequest): OAuthTokens {
        val parameters = Url(callbackUrl).parameters
        parameters["error"]?.let { error ->
            throw CloudAuthException(parameters["error_description"]?.take(200) ?: "Sign-in failed: $error")
        }
        if (parameters["state"] != request.state) throw CloudAuthException("Sign-in response didn't match the request")
        val code = parameters["code"] ?: throw CloudAuthException("Sign-in didn't return an authorization code")
        return tokenRequest(
            Parameters.build {
                append("grant_type", "authorization_code")
                append("code", code)
                append("redirect_uri", config.redirectUri)
                append("client_id", config.clientId)
                append("code_verifier", request.codeVerifier)
            },
            previousRefreshToken = null,
        )
    }

    suspend fun refresh(refreshToken: String): OAuthTokens = tokenRequest(
        Parameters.build {
            append("grant_type", "refresh_token")
            append("refresh_token", refreshToken)
            append("client_id", config.clientId)
            if (config.tokenUrl.contains("microsoftonline")) append("scope", config.scopes.joinToString(" "))
        },
        previousRefreshToken = refreshToken,
    )

    @OptIn(ExperimentalTime::class)
    private suspend fun tokenRequest(form: Parameters, previousRefreshToken: String?): OAuthTokens {
        val parameters = Parameters.build {
            appendAll(form)
            config.clientSecret?.let { append("client_secret", it) }
            config.resource?.let { append("resource", it) }
        }
        val response = try {
            httpClient.submitForm(config.tokenUrl, parameters)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw CloudException("Couldn't reach the sign-in service", e)
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw CloudAuthException("Sign-in expired or was revoked. Connect the account again.")
        }
        val token = runCatching { json.decodeFromString(TokenResponse.serializer(), body) }
            .getOrElse { throw CloudException("Unexpected response from the sign-in service") }
        return OAuthTokens(
            accessToken = token.accessToken,
            refreshToken = token.refreshToken ?: previousRefreshToken,
            expiresAtEpochMs = Clock.System.now().toEpochMilliseconds() + (token.expiresIn - 60).coerceAtLeast(0) * 1000,
        )
    }

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in") val expiresIn: Long = 3600,
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun codeChallenge(verifier: String): String = base64Url(sha256(verifier.encodeToByteArray()))

        private fun base64Url(bytes: ByteArray): String =
            Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(bytes)
    }
}

enum class AccountStatus { NOT_CONFIGURED, DISCONNECTED, CONNECTED }

class CloudAuthManager(
    private val httpClient: HttpClient,
    private val store: SecureFileStore,
    private val platform: PlatformCloudAuth,
    private val googleConfig: OAuthConfig? = OAuthConfigs.googleBrowser(),
    private val microsoftConfig: OAuthConfig? = OAuthConfigs.microsoft(),
) : AccessTokenSource {
    private val mutex = Mutex()
    private val cache = mutableMapOf<CloudAccount, OAuthTokens>()
    private val _status = MutableStateFlow<Map<CloudAccount, AccountStatus>>(emptyMap())
    val status: StateFlow<Map<CloudAccount, AccountStatus>> = _status.asStateFlow()

    suspend fun refreshStatus() {
        val statuses = CloudAccount.entries.associateWith { account ->
            when {
                !isConfigured(account) -> AccountStatus.NOT_CONFIGURED
                load(account) != null -> AccountStatus.CONNECTED
                else -> AccountStatus.DISCONNECTED
            }
        }
        _status.value = statuses
    }

    fun isConfigured(account: CloudAccount): Boolean = when (account) {
        CloudAccount.GOOGLE -> platform.handlesGoogleNatively || googleConfig != null
        CloudAccount.MICROSOFT -> microsoftConfig != null
    }

    suspend fun connect(account: CloudAccount) {
        val tokens = if (account == CloudAccount.GOOGLE && platform.handlesGoogleNatively) {
            OAuthTokens(accessToken = platform.googleAccessToken(interactive = true), platformManaged = true)
        } else {
            val config = configFor(account)
            val client = PkceOAuthClient(httpClient, config)
            val request = client.newRequest()
            client.exchange(platform.browser.authenticate(request.url, config.redirectUri), request)
        }
        mutex.withLock { save(account, tokens) }
        _status.update { it + (account to AccountStatus.CONNECTED) }
    }

    suspend fun disconnect(account: CloudAccount) {
        mutex.withLock {
            cache.remove(account)
            store.delete(fileName(account))
        }
        if (account == CloudAccount.GOOGLE && platform.handlesGoogleNatively) {
            runCatching { platform.googleSignOut() }
        }
        _status.update { it + (account to if (isConfigured(account)) AccountStatus.DISCONNECTED else AccountStatus.NOT_CONFIGURED) }
    }

    @OptIn(ExperimentalTime::class)
    override suspend fun accessToken(account: CloudAccount): String = mutex.withLock {
        val tokens = load(account) ?: throw CloudAuthException("Connect your ${account.displayName} account first")
        if (tokens.platformManaged) return@withLock platform.googleAccessToken(interactive = false)
        if (tokens.expiresAtEpochMs > Clock.System.now().toEpochMilliseconds()) return@withLock tokens.accessToken
        val refreshToken = tokens.refreshToken ?: throw CloudAuthException("Sign-in expired. Connect ${account.displayName} again.")
        val refreshed = try {
            PkceOAuthClient(httpClient, configFor(account)).refresh(refreshToken)
        } catch (e: CloudAuthException) {
            cache.remove(account)
            store.delete(fileName(account))
            _status.update { it + (account to AccountStatus.DISCONNECTED) }
            throw e
        }
        save(account, refreshed)
        refreshed.accessToken
    }

    private fun configFor(account: CloudAccount): OAuthConfig = when (account) {
        CloudAccount.GOOGLE -> googleConfig
        CloudAccount.MICROSOFT -> microsoftConfig
    } ?: throw CloudAuthException("${account.displayName} sign-in isn't configured. Add its client ID to secrets.properties.")

    private suspend fun load(account: CloudAccount): OAuthTokens? {
        cache[account]?.let { return it }
        val tokens = runCatching { json.decodeFromString(OAuthTokens.serializer(), store.read(fileName(account)).decodeToString()) }
            .getOrNull() ?: return null
        cache[account] = tokens
        return tokens
    }

    private suspend fun save(account: CloudAccount, tokens: OAuthTokens) {
        val stored = if (tokens.platformManaged) tokens.copy(accessToken = "") else tokens
        store.write(fileName(account), json.encodeToString(OAuthTokens.serializer(), stored).encodeToByteArray())
        cache[account] = stored
    }

    private fun fileName(account: CloudAccount) = "oauth-${account.name.lowercase()}.bin"

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
