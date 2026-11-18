package com.ashish.allthedocs.ui.cloud

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ashish.allthedocs.cloud.AccountStatus
import com.ashish.allthedocs.cloud.CloudAccount
import com.ashish.allthedocs.cloud.CloudAuthException
import com.ashish.allthedocs.cloud.CloudAuthManager
import com.ashish.allthedocs.cloud.CloudFile
import com.ashish.allthedocs.cloud.CloudProvider
import com.ashish.allthedocs.cloud.CloudSearchService
import com.ashish.allthedocs.cloud.McpServer
import com.ashish.allthedocs.cloud.SearchProvider
import com.ashish.allthedocs.cloud.accountName
import com.ashish.allthedocs.cloud.mcp.McpConnectorManager
import com.ashish.allthedocs.cloud.mcp.McpSearchService
import com.ashish.allthedocs.cloud.mcp.McpServerState
import com.ashish.allthedocs.model.DocumentSearchKeywords
import com.ashish.allthedocs.model.DocumentType
import com.ashish.allthedocs.platform.CapturedFile
import com.ashish.allthedocs.platform.IncomingDocuments
import com.ashish.allthedocs.platform.SUPPORTED_MIME_TYPES
import com.ashish.allthedocs.platform.sniffMimeType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CloudSearchUiState(
    val provider: SearchProvider = CloudProvider.GMAIL,
    val providers: List<SearchProvider> = CloudProvider.entries,
    val query: String = "",
    val accountStatus: AccountStatus? = null,
    val connecting: Boolean = false,
    val searching: Boolean = false,
    val results: List<CloudFile>? = null,
    val downloadingId: String? = null,
    val error: String? = null,
    val readyForReview: Boolean = false,
)

class CloudSearchViewModel(
    expectedTypes: List<DocumentType>,
    private val auth: CloudAuthManager,
    private val search: CloudSearchService,
    private val mcp: McpConnectorManager,
    private val mcpSearch: McpSearchService,
    private val incoming: IncomingDocuments,
) : ViewModel() {
    private val _state = MutableStateFlow(
        CloudSearchUiState(query = DocumentSearchKeywords.forTypes(expectedTypes).joinToString(", ")),
    )
    val state: StateFlow<CloudSearchUiState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        viewModelScope.launch { auth.refreshStatus() }
        viewModelScope.launch { mcp.refreshStatus() }
        viewModelScope.launch {
            combine(auth.status, mcp.servers) { accounts, servers -> accounts to servers }.collect { (accounts, servers) ->
                val current = _state.value.provider
                val provider = if (current is McpServer && servers.none { it.server.id == current.id }) CloudProvider.GMAIL else current
                val status = statusOf(provider, accounts, servers)
                val wasConnected = provider == current && _state.value.accountStatus == AccountStatus.CONNECTED
                _state.update {
                    val switched = provider != current
                    it.copy(
                        provider = provider,
                        providers = CloudProvider.entries + servers.map { entry -> entry.server },
                        accountStatus = status,
                        results = if (switched) null else it.results,
                        error = if (switched) null else it.error,
                    )
                }
                if (status == AccountStatus.CONNECTED && !wasConnected && _state.value.results == null) runSearch()
            }
        }
    }

    fun onQueryChange(value: String) = _state.update { it.copy(query = value) }

    fun selectProvider(provider: SearchProvider) {
        job?.cancel()
        val status = statusOf(provider, auth.status.value, mcp.servers.value)
        _state.update {
            it.copy(provider = provider, accountStatus = status, results = null, error = null, searching = false, downloadingId = null)
        }
        if (status == AccountStatus.CONNECTED) runSearch()
    }

    fun connect() {
        val provider = _state.value.provider
        job?.cancel()
        _state.update { it.copy(connecting = true, error = null) }
        job = viewModelScope.launch {
            try {
                when (provider) {
                    is CloudProvider -> auth.connect(provider.account)
                    is McpServer -> mcp.connect(provider)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Couldn't connect ${provider.accountName}") }
            } finally {
                _state.update { it.copy(connecting = false) }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.update { it.copy(connecting = false, searching = false, downloadingId = null) }
    }

    fun runSearch() {
        val current = _state.value
        val keywords = current.query.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (keywords.isEmpty()) {
            _state.update { it.copy(error = "Enter at least one keyword") }
            return
        }
        job?.cancel()
        _state.update { it.copy(searching = true, error = null) }
        job = viewModelScope.launch {
            try {
                val results = when (val provider = current.provider) {
                    is CloudProvider -> search.search(provider, keywords)
                    is McpServer -> mcpSearch.search(provider, keywords)
                }
                _state.update { it.copy(results = results) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleError(e, "Search failed")
            } finally {
                _state.update { it.copy(searching = false) }
            }
        }
    }

    fun pick(file: CloudFile) {
        if (_state.value.downloadingId != null) return
        _state.update { it.copy(downloadingId = file.id, error = null) }
        job = viewModelScope.launch {
            try {
                val bytes = when (file.provider) {
                    is CloudProvider -> search.download(file)
                    is McpServer -> mcpSearch.download(file)
                }
                val mimeType = sniffMimeType(bytes) ?: file.mimeType
                if (mimeType !in SUPPORTED_MIME_TYPES) {
                    _state.update { it.copy(error = "Only PDF, JPEG and PNG documents can be added") }
                    return@launch
                }
                incoming.handOff(CapturedFile(bytes, mimeType, file.name, file.provider.source))
                _state.update { it.copy(readyForReview = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleError(e, "Download failed")
            } finally {
                _state.update { it.copy(downloadingId = null) }
            }
        }
    }

    fun onNavigatedToReview() = _state.update { it.copy(readyForReview = false) }

    private fun statusOf(
        provider: SearchProvider,
        accounts: Map<CloudAccount, AccountStatus>,
        servers: List<McpServerState>,
    ): AccountStatus? = when (provider) {
        is CloudProvider -> accounts[provider.account]
        is McpServer -> servers.firstOrNull { it.server.id == provider.id }?.status
    }

    private suspend fun handleError(e: Exception, fallback: String) {
        if (e is CloudAuthException) {
            auth.refreshStatus()
            (_state.value.provider as? McpServer)?.let { mcp.disconnect(it) }
        }
        _state.update { it.copy(error = e.message ?: fallback) }
    }
}
