package com.ashish.allthedocs.cloud.mcp

import com.ashish.allthedocs.cloud.AccountStatus
import com.ashish.allthedocs.cloud.CloudAuthException
import com.ashish.allthedocs.cloud.CloudException
import com.ashish.allthedocs.cloud.McpServer
import com.ashish.allthedocs.cloud.OAuthConfig
import com.ashish.allthedocs.cloud.OAuthConfigs
import com.ashish.allthedocs.cloud.OAuthTokens
import com.ashish.allthedocs.cloud.PkceOAuthClient
import com.ashish.allthedocs.cloud.PlatformCloudAuth
import com.ashish.allthedocs.platform.SecureFileStore
import io.ktor.client.HttpClient
import io.ktor.http.Url
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class McpConnectorManager(
    private val httpClient: HttpClient,
    private val store: SecureFileStore,
    private val platform: PlatformCloudAuth,
    private val discovery: McpAuthDiscovery = McpAuthDiscovery(httpClient),
    private val redirectUri: String = OAuthConfigs.MCP_REDIRECT_URI,
) : McpTokenSource {
    private val mutex = Mutex()
    private var records: List<McpServerRecord>? = null
    private val tokenCache = mutableMapOf<String, OAuthTokens>()
    private val _servers = MutableStateFlow<List<McpServerState>>(emptyList())
    val servers: StateFlow<List<McpServerState>> = _servers.asStateFlow()

    suspend fun refreshStatus() = mutex.withLock { publish() }

    suspend fun add(
        name: String,
        url: String,
        clientId: String? = null,
        clientSecret: String? = null,
        preset: McpPreset? = null,
    ): McpServer {
        val trimmedName = name.trim().ifEmpty { throw CloudException("Give the app a name") }
        val canonical = canonicalUrl(url)
        if (mutex.withLock { loadRecords().any { it.url == canonical } }) throw CloudException("$canonical is already added")
        val record = describe(trimmedName, canonical, clientId?.trim()?.ifEmpty { null }, clientSecret?.trim()?.ifEmpty { null }, preset)
        if (record.authRequired) authorize(record)
        mutex.withLock {
            records = loadRecords().filterNot { it.id == record.id } + record
            persistRecords()
            publish()
        }
        return record.server
    }

    suspend fun connect(server: McpServer) {
        val record = recordFor(server)
        if (!record.authRequired) return
        authorize(record)
        mutex.withLock { publish() }
    }

    suspend fun disconnect(server: McpServer) = mutex.withLock {
        clearTokens(server.id)
        publish()
    }

    suspend fun remove(server: McpServer) = mutex.withLock {
        clearTokens(server.id)
        records = loadRecords().filterNot { it.id == server.id }
        persistRecords()
        publish()
    }

    @OptIn(ExperimentalTime::class)
    override suspend fun accessToken(server: McpServer): String? = mutex.withLock {
        val record = loadRecords().firstOrNull { it.id == server.id } ?: throw CloudAuthException(notSetUp(server))
        if (!record.authRequired) return@withLock null
        val tokens = loadTokens(record.id) ?: throw CloudAuthException("Connect ${server.name} first")
        if (tokens.expiresAtEpochMs > Clock.System.now().toEpochMilliseconds()) return@withLock tokens.accessToken
        val refreshToken = tokens.refreshToken ?: throw CloudAuthException("Sign-in expired. Connect ${server.name} again.")
        val refreshed = try {
            PkceOAuthClient(httpClient, configFor(record)).refresh(refreshToken)
        } catch (e: CloudAuthException) {
            clearTokens(record.id)
            publish()
            throw e
        }
        saveTokens(record.id, refreshed)
        refreshed.accessToken
    }

    private suspend fun describe(
        name: String,
        url: String,
        clientId: String?,
        clientSecret: String?,
        preset: McpPreset?,
    ): McpServerRecord {
        val probe = discovery.probe(url)
        val id = newId()
        if (!probe.authRequired) return McpServerRecord(id, name, url, authRequired = false)
        val resource = discovery.protectedResource(url, probe.resourceMetadataUrl)
        val issuer = resource?.authorizationServers?.firstOrNull() ?: McpAuthDiscovery.origin(Url(url))
        val authServer = discovery.authorizationServer(issuer)
        val authorizeUrl = authServer?.authorizationEndpoint ?: preset?.authorizeUrl
            ?: throw CloudAuthException("Couldn't find how to sign in to $name. Check the server URL.")
        val tokenUrl = authServer?.tokenEndpoint ?: preset?.tokenUrl
            ?: throw CloudAuthException("Couldn't find how to sign in to $name. Check the server URL.")
        val registrationEndpoint = authServer?.registrationEndpoint
        val registration = when {
            clientId != null -> McpAuthDiscovery.Registration(clientId, clientSecret)
            registrationEndpoint != null -> discovery.register(registrationEndpoint, redirectUri)
            else -> throw CloudAuthException(
                "$name needs an OAuth client ID. Register an app with the redirect URI $redirectUri and enter its client ID.",
            )
        }
        val scopes = probe.scope?.split(' ')?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
            ?: preset?.scopes?.takeIf { it.isNotEmpty() }
            ?: resource?.scopesSupported.orEmpty()
        return McpServerRecord(
            id = id,
            name = name,
            url = url,
            authRequired = true,
            clientId = registration.clientId,
            clientSecret = registration.clientSecret,
            authorizeUrl = authorizeUrl,
            tokenUrl = tokenUrl,
            scopes = scopes,
            extraParameters = preset?.extraParameters.orEmpty(),
        )
    }

    private suspend fun authorize(record: McpServerRecord) {
        val config = configFor(record)
        val client = PkceOAuthClient(httpClient, config)
        val request = client.newRequest()
        val tokens = client.exchange(platform.browser.authenticate(request.url, config.redirectUri), request)
        mutex.withLock { saveTokens(record.id, tokens) }
    }

    private suspend fun recordFor(server: McpServer): McpServerRecord =
        mutex.withLock { loadRecords().firstOrNull { it.id == server.id } } ?: throw CloudAuthException(notSetUp(server))

    private fun configFor(record: McpServerRecord): OAuthConfig = OAuthConfig(
        authorizeUrl = record.authorizeUrl ?: throw CloudAuthException(notSetUp(record.server)),
        tokenUrl = record.tokenUrl ?: throw CloudAuthException(notSetUp(record.server)),
        clientId = record.clientId ?: throw CloudAuthException(notSetUp(record.server)),
        redirectUri = redirectUri,
        scopes = record.scopes,
        extraParameters = record.extraParameters,
        clientSecret = record.clientSecret,
        resource = record.url,
    )

    private fun notSetUp(server: McpServer) = "${server.name} isn't set up for sign-in. Remove it and add it again."

    private suspend fun loadRecords(): List<McpServerRecord> = records ?: runCatching {
        json.decodeFromString(ListSerializer(McpServerRecord.serializer()), store.read(REGISTRY_FILE).decodeToString())
    }.getOrDefault(emptyList()).also { records = it }

    private suspend fun persistRecords() {
        store.write(REGISTRY_FILE, json.encodeToString(ListSerializer(McpServerRecord.serializer()), records.orEmpty()).encodeToByteArray())
    }

    private suspend fun publish() {
        _servers.value = loadRecords().map { record ->
            val connected = !record.authRequired || loadTokens(record.id) != null
            McpServerState(record.server, if (connected) AccountStatus.CONNECTED else AccountStatus.DISCONNECTED)
        }
    }

    private suspend fun loadTokens(id: String): OAuthTokens? {
        tokenCache[id]?.let { return it }
        val tokens = runCatching { json.decodeFromString(OAuthTokens.serializer(), store.read(tokenFile(id)).decodeToString()) }
            .getOrNull() ?: return null
        tokenCache[id] = tokens
        return tokens
    }

    private suspend fun saveTokens(id: String, tokens: OAuthTokens) {
        store.write(tokenFile(id), json.encodeToString(OAuthTokens.serializer(), tokens).encodeToByteArray())
        tokenCache[id] = tokens
    }

    private suspend fun clearTokens(id: String) {
        tokenCache.remove(id)
        store.delete(tokenFile(id))
    }

    private fun tokenFile(id: String) = "oauth-mcp-$id.bin"

    companion object {
        private const val REGISTRY_FILE = "mcp-servers.bin"
        private val json = Json { ignoreUnknownKeys = true }
        private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")

        internal fun canonicalUrl(input: String): String {
            val trimmed = input.trim().substringBefore('#').trimEnd('/')
            val url = runCatching { Url(trimmed) }.getOrNull()
            if (url == null || url.host.isBlank() || !trimmed.contains("://")) {
                throw CloudException("Enter the server's full URL, like https://mcp.example.com/mcp")
            }
            val secure = url.protocol.name == "https" || (url.protocol.name == "http" && url.host in LOCAL_HOSTS)
            if (!secure) throw CloudException("The server URL must start with https://")
            return trimmed
        }

        @OptIn(ExperimentalUuidApi::class)
        private fun newId() = Uuid.random().toString()
    }
}
