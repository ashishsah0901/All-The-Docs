package com.ashish.allthedocs.ui.cloud

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ashish.allthedocs.cloud.AccountStatus
import com.ashish.allthedocs.cloud.CloudFile
import com.ashish.allthedocs.cloud.CloudProvider
import com.ashish.allthedocs.cloud.McpServer
import com.ashish.allthedocs.cloud.accountName
import com.ashish.allthedocs.model.DocumentType
import com.ashish.allthedocs.ui.AppTopBar
import com.ashish.allthedocs.ui.EmptyState
import com.ashish.allthedocs.ui.formatSize
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun CloudSearchScreen(
    expectedTypes: List<DocumentType>,
    onBack: () -> Unit,
    onReview: () -> Unit,
    viewModel: CloudSearchViewModel = koinViewModel { parametersOf(expectedTypes) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.readyForReview) {
        if (state.readyForReview) {
            viewModel.onNavigatedToReview()
            onReview()
        }
    }

    Scaffold(topBar = { AppTopBar("Find in your accounts", onBack = onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.providers) { provider ->
                        FilterChip(
                            selected = state.provider == provider,
                            onClick = { viewModel.selectProvider(provider) },
                            label = { Text(provider.displayName) },
                        )
                    }
                }
            }
            item { AccountSection(state, viewModel) }
            if (state.accountStatus == AccountStatus.CONNECTED) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = viewModel::onQueryChange,
                            label = { Text("Keywords (comma separated)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(onClick = viewModel::runSearch, enabled = !state.searching, modifier = Modifier.fillMaxWidth()) {
                            Text("Search ${state.provider.displayName}")
                        }
                    }
                }
            }
            if (state.searching) {
                item { Progress("Searching ${state.provider.displayName}…") }
            }
            state.error?.let { error ->
                item {
                    Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
            val results = state.results
            if (results != null && !state.searching) {
                if (results.isEmpty()) {
                    item { EmptyState("No matching documents", "Try other keywords or another account.") }
                }
                items(results, key = { "${it.provider}-${it.parentId}-${it.id}" }) { file ->
                    ResultCard(file, downloading = state.downloadingId == file.id, onClick = { viewModel.pick(file) })
                }
            }
        }
    }
}

@Composable
private fun AccountSection(state: CloudSearchUiState, viewModel: CloudSearchViewModel) {
    val provider = state.provider
    val account = provider.accountName
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state.accountStatus) {
            AccountStatus.NOT_CONFIGURED -> Text(
                "$account sign-in isn't set up in this build. Add its client ID to secrets.properties and rebuild.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AccountStatus.DISCONNECTED -> {
                Text(
                    when (provider) {
                        is CloudProvider ->
                            "Connect your $account account to search ${provider.displayName}. " +
                                "Access is read-only, and files are fetched straight to this device."
                        is McpServer ->
                            "Connect $account to search it. You sign in with $account itself, " +
                                "and files are fetched straight to this device."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (state.connecting) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Progress("Waiting for $account sign-in…")
                        TextButton(onClick = viewModel::cancel) { Text("Cancel") }
                    }
                } else {
                    Button(onClick = viewModel::connect) { Text("Connect $account") }
                }
            }
            AccountStatus.CONNECTED -> Text(
                "$account connected",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            null -> Progress("Checking accounts…")
        }
    }
}

@Composable
private fun ResultCard(file: CloudFile, downloading: Boolean, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable(enabled = !downloading, onClick = onClick)) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(file.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                file.context?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    listOfNotNull(file.modified?.take(10), file.sizeBytes?.let(::formatSize)).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (downloading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun Progress(text: String) {
    Row(
        Modifier.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
