package com.ashish.allthedocs.ui.scenario

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ashish.allthedocs.api.DocumentRequirement
import com.ashish.allthedocs.api.SourceLink
import com.ashish.allthedocs.checklist.RequirementState
import com.ashish.allthedocs.checklist.RequirementStatus
import com.ashish.allthedocs.model.DocumentType
import com.ashish.allthedocs.ui.AppTopBar
import com.ashish.allthedocs.ui.EmptyState
import com.ashish.allthedocs.ui.StatusChip
import com.ashish.allthedocs.ui.Tone
import com.ashish.allthedocs.ui.label
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun ScenarioDetailScreen(
    scenarioId: String,
    onBack: () -> Unit,
    onAddDocument: (List<DocumentType>, String) -> Unit,
    onFindInAccounts: (List<DocumentType>, String) -> Unit,
    onOpenDocument: (String) -> Unit,
    viewModel: ScenarioDetailViewModel = koinViewModel(key = scenarioId) { parametersOf(scenarioId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            AppTopBar(
                title = state.scenario?.title ?: "Checklist",
                onBack = onBack,
                actions = {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete scenario")
                    }
                },
            )
        },
    ) { padding ->
        val scenario = state.scenario
        when {
            !state.loaded -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            scenario == null -> EmptyState("Scenario not found", "It may have been deleted.", Modifier.padding(padding))
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "${state.foundCount} of ${state.statuses.size} documents ready",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        LinearProgressIndicator(
                            progress = { if (state.statuses.isEmpty()) 0f else state.foundCount / state.statuses.size.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            listOfNotNull(scenario.countryCode, scenario.origin.label()).joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        scenario.assumptions.forEach { assumption ->
                            Text(
                                "• $assumption",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                items(state.statuses, key = { it.requirement.id }) { status ->
                    RequirementCard(
                        status = status,
                        onAddDocument = {
                            onAddDocument(status.requirement.acceptedTypes, status.requirement.name)
                        },
                        onFindInAccounts = {
                            onFindInAccounts(status.requirement.acceptedTypes, status.requirement.name)
                        },
                        onOpenDocument = onOpenDocument,
                    )
                }
                item {
                    Text(
                        "Requirements change. Always confirm with the official source before you apply.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this scenario?") },
            text = { Text("Documents in your vault are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete(onBack)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RequirementCard(
    status: RequirementStatus,
    onAddDocument: () -> Unit,
    onFindInAccounts: () -> Unit,
    onOpenDocument: (String) -> Unit,
) {
    val requirement = status.requirement
    var expanded by rememberSaveable(requirement.id) { mutableStateOf(status.state != RequirementState.FOUND) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(requirement.name, style = MaterialTheme.typography.titleSmall)
                    if (!requirement.mandatory) {
                        Text("Optional", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                when (status.state) {
                    RequirementState.FOUND -> StatusChip("Found", Tone.Positive)
                    RequirementState.NEEDS_ACTION -> StatusChip("Needs action", Tone.Warning)
                    RequirementState.MISSING -> StatusChip("Missing", if (requirement.mandatory) Tone.Negative else Tone.Neutral)
                }
            }

            status.matchedDocument?.let { doc ->
                Text(
                    "From vault: ${doc.title}" + (status.reason?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onOpenDocument(doc.id) },
                )
            }

            if (expanded) {
                if (requirement.description.isNotBlank()) {
                    Text(requirement.description, style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "Accepted: " + requirement.acceptedTypes.joinToString(", ") { it.displayName },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (requirement.specs.isNotEmpty()) {
                    BulletList("Requirements", requirement.specs)
                }
                if (status.state != RequirementState.FOUND) {
                    ObtainGuideSection(requirement)
                }
                CitationsSection(requirement)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onAddDocument) {
                        Text(if (status.state == RequirementState.FOUND) "Add another" else "Add from device")
                    }
                    OutlinedButton(onClick = onFindInAccounts) { Text("Find in email, drives & apps") }
                }
            }
        }
    }
}

@Composable
private fun ObtainGuideSection(requirement: DocumentRequirement) {
    val guide = requirement.obtainGuide ?: return
    HorizontalDivider()
    Text("How to get it", style = MaterialTheme.typography.labelLarge)
    guide.steps.forEachIndexed { index, step ->
        Text("${index + 1}. $step", style = MaterialTheme.typography.bodySmall)
    }
    val meta = listOfNotNull(guide.typicalFee?.let { "Fee: $it" }, guide.typicalTime?.let { "Time: $it" })
    if (meta.isNotEmpty()) {
        Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    guide.links.forEach { LinkText(it) }
}

@Composable
private fun CitationsSection(requirement: DocumentRequirement) {
    if (requirement.isCited) {
        Text("Sources", style = MaterialTheme.typography.labelLarge)
        requirement.citations.forEach { LinkText(it) }
    } else {
        StatusChip("Unverified: no official source cited", Tone.Neutral)
    }
}

@Composable
private fun BulletList(title: String, items: List<String>) {
    Text(title, style = MaterialTheme.typography.labelLarge)
    items.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun LinkText(link: SourceLink) {
    val uriHandler = LocalUriHandler.current
    Text(
        link.title.ifBlank { link.url },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier.clickable { runCatching { uriHandler.openUri(link.url) } },
    )
}
