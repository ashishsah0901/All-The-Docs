package com.ashish.allthedocs.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ashish.allthedocs.cloud.AccountStatus
import com.ashish.allthedocs.cloud.CloudAccount
import com.ashish.allthedocs.cloud.CloudAuthManager
import com.ashish.allthedocs.cloud.McpServer
import com.ashish.allthedocs.cloud.mcp.McpConnectorManager
import com.ashish.allthedocs.cloud.mcp.McpPreset
import com.ashish.allthedocs.cloud.mcp.McpPresets
import com.ashish.allthedocs.cloud.mcp.McpServerState
import com.ashish.allthedocs.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val serverUrl: String = "",
    val holderName: String = "",
    val loaded: Boolean = false,
    val savedMessage: String? = null,
)

data class McpFormState(
    val preset: McpPreset? = McpPresets.all.first(),
    val name: String = McpPresets.all.first().name,
    val url: String = McpPresets.all.first().url,
    val clientId: String = "",
    val clientSecret: String = "",
    val busyId: String? = null,
    val error: String? = null,
    val message: String? = null,
)

class SettingsViewModel(
    private val repository: SettingsRepository,
    private val cloudAuth: CloudAuthManager,
    private val mcp: McpConnectorManager,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    val accounts: StateFlow<Map<CloudAccount, AccountStatus>> = cloudAuth.status

    private val _accountBusy = MutableStateFlow<CloudAccount?>(null)
    val accountBusy: StateFlow<CloudAccount?> = _accountBusy.asStateFlow()

    private val _accountError = MutableStateFlow<String?>(null)
    val accountError: StateFlow<String?> = _accountError.asStateFlow()

    val mcpServers: StateFlow<List<McpServerState>> = mcp.servers

    private val _mcpForm = MutableStateFlow(McpFormState())
    val mcpForm: StateFlow<McpFormState> = _mcpForm.asStateFlow()

    fun connect(account: CloudAccount) = accountAction(account) { cloudAuth.connect(account) }

    fun disconnect(account: CloudAccount) = accountAction(account) { cloudAuth.disconnect(account) }

    private fun accountAction(account: CloudAccount, block: suspend () -> Unit) {
        if (_accountBusy.value != null) return
        _accountBusy.value = account
        _accountError.value = null
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _accountError.value = e.message ?: "Couldn't update ${account.displayName}"
            } finally {
                _accountBusy.value = null
            }
        }
    }

    fun selectMcpPreset(preset: McpPreset?) = _mcpForm.update {
        it.copy(
            preset = preset,
            name = preset?.name.orEmpty(),
            url = preset?.url.orEmpty(),
            clientId = "",
            clientSecret = "",
            error = null,
            message = null,
        )
    }

    fun onMcpNameChange(value: String) = _mcpForm.update { it.copy(name = value, error = null, message = null) }

    fun onMcpUrlChange(value: String) = _mcpForm.update { it.copy(url = value, error = null, message = null) }

    fun onMcpClientIdChange(value: String) = _mcpForm.update { it.copy(clientId = value, error = null, message = null) }

    fun onMcpClientSecretChange(value: String) = _mcpForm.update { it.copy(clientSecret = value, error = null, message = null) }

    fun addMcp() {
        val form = _mcpForm.value
        val preset = form.preset?.takeIf { it.url == form.url.trim().trimEnd('/') }
        mcpAction(NEW_MCP) {
            val server = mcp.add(form.name, form.url, form.clientId, form.clientSecret, preset)
            _mcpForm.update { it.copy(clientId = "", clientSecret = "", message = "${server.name} connected") }
        }
    }

    fun connectMcp(server: McpServer) = mcpAction(server.id) { mcp.connect(server) }

    fun disconnectMcp(server: McpServer) = mcpAction(server.id) { mcp.disconnect(server) }

    fun removeMcp(server: McpServer) = mcpAction(server.id) { mcp.remove(server) }

    private fun mcpAction(id: String, block: suspend () -> Unit) {
        if (_mcpForm.value.busyId != null) return
        _mcpForm.update { it.copy(busyId = id, error = null, message = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _mcpForm.update { it.copy(error = e.message ?: "Couldn't update the app") }
            } finally {
                _mcpForm.update { it.copy(busyId = null) }
            }
        }
    }

    init {
        viewModelScope.launch { cloudAuth.refreshStatus() }
        viewModelScope.launch { mcp.refreshStatus() }
        viewModelScope.launch {
            val serverUrl = repository.serverUrl.first()
            val holderName = repository.holderName.first()
            _state.update { it.copy(serverUrl = serverUrl, holderName = holderName, loaded = true) }
        }
    }

    fun onServerUrlChange(value: String) = _state.update { it.copy(serverUrl = value, savedMessage = null) }

    fun onHolderNameChange(value: String) = _state.update { it.copy(holderName = value, savedMessage = null) }

    fun save() {
        val current = _state.value
        viewModelScope.launch {
            repository.setServerUrl(current.serverUrl)
            repository.setHolderName(current.holderName)
            _state.update { it.copy(savedMessage = "Saved") }
        }
    }

    companion object {
        const val NEW_MCP = "new"
    }
}
