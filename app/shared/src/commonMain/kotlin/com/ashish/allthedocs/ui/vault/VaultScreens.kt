package com.ashish.allthedocs.ui.vault

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ashish.allthedocs.api.SignatureStatus
import com.ashish.allthedocs.checklist.ChecklistEvaluator
import com.ashish.allthedocs.data.VaultDocument
import com.ashish.allthedocs.model.DocumentCategory
import com.ashish.allthedocs.model.VaultSection
import com.ashish.allthedocs.ui.AppTopBar
import com.ashish.allthedocs.ui.EmptyState
import com.ashish.allthedocs.ui.StatusChip
import com.ashish.allthedocs.ui.Tone
import com.ashish.allthedocs.ui.formatSize
import com.ashish.allthedocs.ui.today
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.plus
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun VaultListScreen(
    onAddDocument: () -> Unit,
    onOpenDocument: (String) -> Unit,
    viewModel: VaultListViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = { AppTopBar("Vault") },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddDocument,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Upload") },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val sections = VaultSection.entries
            PrimaryTabRow(selectedTabIndex = sections.indexOf(state.filters.section)) {
                sections.forEach { section ->
                    Tab(
                        selected = state.filters.section == section,
                        onClick = { viewModel.onSectionChange(section) },
                        text = { Text("${section.displayName} (${state.count(section)})") },
                    )
                }
            }
            if (state.documents == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                VaultListBody(state, viewModel, onOpenDocument)
            }
        }
    }
}

@Composable
private fun VaultListBody(
    state: VaultListUiState,
    viewModel: VaultListViewModel,
    onOpenDocument: (String) -> Unit,
) {
    val groups = state.groups
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            OutlinedTextField(
                value = state.filters.query,
                onValueChange = viewModel::onQueryChange,
                placeholder = { Text("Search by name, type or issuer") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (state.categories.size > 1) {
            item {
                CategoryChips(state.categories, state.filters.category, viewModel::onCategoryChange)
            }
        }
        if (groups.isEmpty()) {
            item { SectionEmptyState(state) }
        }
        groups.forEach { (category, documents) ->
            item(key = "header-${category.name}") {
                Text(
                    "${category.displayName} · ${documents.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                )
            }
            items(documents, key = { it.id }) { document ->
                VaultDocumentCard(document, onClick = { onOpenDocument(document.id) })
            }
        }
    }
}

@Composable
private fun CategoryChips(
    categories: List<DocumentCategory>,
    selected: DocumentCategory?,
    onSelect: (DocumentCategory?) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("All") })
        }
        items(categories) { category ->
            FilterChip(
                selected = selected == category,
                onClick = { onSelect(if (selected == category) null else category) },
                label = { Text(category.displayName) },
            )
        }
    }
}

@Composable
private fun SectionEmptyState(state: VaultListUiState) {
    val (title, body) = when {
        state.filters.query.isNotBlank() || state.filters.category != null ->
            "No matches" to "Try a different search or category."
        state.filters.section == VaultSection.ISSUED ->
            "No issued documents yet" to
                "Official documents land here once they're verified with no issues. " +
                "Open one in Uploaded and tap Verify now."
        else -> "Nothing uploaded yet" to
            "Scan or import documents. They're encrypted and stored only on this device."
    }
    EmptyState(title, body, Modifier.padding(top = 24.dp))
}

@Composable
private fun VaultDocumentCard(document: VaultDocument, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable(onClick = onClick)) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(document.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    document.issuer?.let { "Issued by $it" } ?: document.type.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ValidityChip(document)
        }
    }
}

@Composable
private fun ValidityChip(document: VaultDocument) {
    val expiry = document.expiryDate
    val now = today()
    when {
        expiry != null && expiry < now -> StatusChip("Expired", Tone.Negative)
        expiry != null && expiry <= now.plus(DatePeriod(days = ChecklistEvaluator.EXPIRY_WARNING_DAYS)) ->
            StatusChip("Expires $expiry", Tone.Warning)
        document.issues.isNotEmpty() -> StatusChip("Check", Tone.Warning)
        !document.verified -> StatusChip("Unverified", Tone.Neutral)
        else -> StatusChip("Valid", Tone.Positive)
    }
}

@Composable
fun VaultDocumentScreen(
    documentId: String,
    onBack: () -> Unit,
    viewModel: VaultDocumentViewModel = koinViewModel(key = documentId) { parametersOf(documentId) },
) {
    val document by viewModel.document.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val verifying by viewModel.verifying.collectAsStateWithLifecycle()
    val info by viewModel.info.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            AppTopBar(
                title = document?.title ?: "Document",
                onBack = onBack,
                actions = {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete document")
                    }
                },
            )
        },
    ) { padding ->
        val doc = document
        if (doc == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusChip(
                        "${doc.section.displayName} document",
                        if (doc.section == VaultSection.ISSUED) Tone.Positive else Tone.Neutral,
                    )
                    ValidityChip(doc)
                }
                DetailRow("Type", doc.type.displayName)
                DetailRow("Category", doc.type.category.displayName)
                doc.issuer?.let { DetailRow("Issued by", it) }
                doc.holderName?.let { DetailRow("Holder", it) }
                doc.issueDate?.let { DetailRow("Issued on", it.toString()) }
                doc.expiryDate?.let { DetailRow("Expires", it.toString()) }
                doc.source?.let { DetailRow("Source", it.displayName) }
                doc.signatureStatus?.let { DetailRow("Digital signature", signatureLabel(it, doc.signedBy)) }
                DetailRow("File", "${doc.originalFileName} · ${formatSize(doc.sizeBytes)}")
                DetailRow("Verification", if (doc.verified) doc.verificationSummary ?: "Verified" else "Not verified")
                if (doc.issues.isNotEmpty()) {
                    Text("Issues", style = MaterialTheme.typography.labelLarge)
                    doc.issues.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
                if (doc.section == VaultSection.UPLOADED && doc.type.isIssuable) {
                    Text(
                        "Once it's verified with no issues, this document moves to Issued.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = viewModel::reverify, enabled = !verifying, modifier = Modifier.fillMaxWidth()) {
                        if (verifying) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text(if (doc.verified) "Verify again" else "Verify now")
                        }
                    }
                }
                Button(onClick = viewModel::open, modifier = Modifier.fillMaxWidth()) { Text("Open document") }
                info?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this document?") },
            text = { Text("The encrypted file is removed from this device and its reminders are cancelled.") },
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

private fun signatureLabel(status: SignatureStatus, signedBy: String?): String = when (status) {
    SignatureStatus.VALID -> "Valid" + (signedBy?.let { ", signed by $it" } ?: "")
    SignatureStatus.UNTRUSTED -> "Signed by an unrecognised signer" + (signedBy?.let { " ($it)" } ?: "")
    SignatureStatus.INVALID -> "Invalid: the file may have been changed"
    SignatureStatus.NOT_SIGNED -> "Not signed"
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
