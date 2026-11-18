package com.ashish.allthedocs.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ashish.allthedocs.cloud.AccountStatus
import com.ashish.allthedocs.cloud.CloudAccount
import com.ashish.allthedocs.cloud.OAuthConfigs
import com.ashish.allthedocs.cloud.mcp.McpPresets
import com.ashish.allthedocs.cloud.mcp.McpServerState
import com.ashish.allthedocs.ui.AppTopBar
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val accountBusy by viewModel.accountBusy.collectAsStateWithLifecycle()
    val accountError by viewModel.accountError.collectAsStateWithLifecycle()
    val mcpServers by viewModel.mcpServers.collectAsStateWithLifecycle()
    val mcpForm by viewModel.mcpForm.collectAsStateWithLifecycle()
    Scaffold(topBar = { AppTopBar("Settings") }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = state.holderName,
                onValueChange = viewModel::onHolderNameChange,
                label = { Text("Your full name") },
                supportingText = { Text("Used to check that a document belongs to you") },
                singleLine = true,
                enabled = state.loaded,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.serverUrl,
                onValueChange = viewModel::onServerUrlChange,
                label = { Text("Server URL") },
                supportingText = { Text("The AllTheDocs server that resolves requirements and verifies documents") },
                singleLine = true,
                enabled = state.loaded,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = viewModel::save, enabled = state.loaded, modifier = Modifier.fillMaxWidth()) {
                Text("Save")
            }
            state.savedMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            HorizontalDivider()
            Text("Connected accounts", style = MaterialTheme.typography.titleMedium)
            CloudAccount.entries.forEach { account ->
                val status = accounts[account]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(account.displayName, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            when (status) {
                                AccountStatus.CONNECTED -> "Connected (read-only)"
                                AccountStatus.DISCONNECTED -> "Not connected"
                                AccountStatus.NOT_CONFIGURED -> "Not set up in this build"
                                null -> "Checking…"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    when {
                        accountBusy == account -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        status == AccountStatus.CONNECTED ->
                            TextButton(onClick = { viewModel.disconnect(account) }) { Text("Disconnect") }
                        status == AccountStatus.DISCONNECTED ->
                            TextButton(onClick = { viewModel.connect(account) }, enabled = accountBusy == null) { Text("Connect") }
                        else -> Unit
                    }
                }
            }
            accountError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            HorizontalDivider()
            ConnectedAppsSection(mcpServers, mcpForm, viewModel)
        }
    }
}

@Composable
private fun ConnectedAppsSection(servers: List<McpServerState>, form: McpFormState, viewModel: SettingsViewModel) {
    Text("Connected apps (MCP)", style = MaterialTheme.typography.titleMedium)
    Text(
        "Any cloud service with a remote MCP server can be searched like Gmail or Drive. " +
            "You sign in with the service itself, and tokens stay on this device.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    servers.forEach { entry -> McpServerRow(entry, form.busyId, viewModel) }
    Text("Add an app", style = MaterialTheme.typography.titleSmall)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        McpPresets.all.forEach { preset ->
            FilterChip(
                selected = form.preset == preset,
                onClick = { viewModel.selectMcpPreset(preset) },
                label = { Text(preset.name) },
            )
        }
        FilterChip(selected = form.preset == null, onClick = { viewModel.selectMcpPreset(null) }, label = { Text("Custom") })
    }
    OutlinedTextField(
        value = form.name,
        onValueChange = viewModel::onMcpNameChange,
        label = { Text("Name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = form.url,
        onValueChange = viewModel::onMcpUrlChange,
        label = { Text("MCP server URL") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = form.clientId,
        onValueChange = viewModel::onMcpClientIdChange,
        label = { Text(if (form.preset?.needsClientId == true) "Client ID" else "Client ID (optional)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = form.clientSecret,
        onValueChange = viewModel::onMcpClientSecretChange,
        label = { Text(if (form.preset?.needsClientSecret == true) "Client secret" else "Client secret (optional)") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        form.preset?.hint
            ?: "Enter the server's MCP URL. If the service needs a registered app, give it the redirect URI below and enter the client ID.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SelectionContainer {
        Text("Redirect URI: ${OAuthConfigs.MCP_REDIRECT_URI}", style = MaterialTheme.typography.bodySmall)
    }
    Button(
        onClick = viewModel::addMcp,
        enabled = form.busyId == null && form.name.isNotBlank() && form.url.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (form.busyId == SettingsViewModel.NEW_MCP) "Connecting…" else "Connect")
    }
    form.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    form.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
}

@Composable
private fun McpServerRow(entry: McpServerState, busyId: String?, viewModel: SettingsViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(entry.server.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${entry.server.host} · ${if (entry.status == AccountStatus.CONNECTED) "Connected" else "Not connected"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            busyId == entry.server.id -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            entry.status == AccountStatus.CONNECTED ->
                TextButton(onClick = { viewModel.disconnectMcp(entry.server) }, enabled = busyId == null) { Text("Disconnect") }
            else -> TextButton(onClick = { viewModel.connectMcp(entry.server) }, enabled = busyId == null) { Text("Connect") }
        }
        TextButton(onClick = { viewModel.removeMcp(entry.server) }, enabled = busyId == null) { Text("Remove") }
    }
}
