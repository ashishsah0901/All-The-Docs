package com.ashish.allthedocs.ui.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ashish.allthedocs.api.SignatureStatus
import com.ashish.allthedocs.api.VerificationResult
import com.ashish.allthedocs.model.DocumentType
import com.ashish.allthedocs.platform.rememberDocumentCaptureLauncher
import com.ashish.allthedocs.ui.AppTopBar
import com.ashish.allthedocs.ui.StatusChip
import com.ashish.allthedocs.ui.Tone
import com.ashish.allthedocs.ui.formatSize
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun AddDocumentScreen(
    expectedTypes: List<DocumentType>,
    suggestedTitle: String,
    incoming: Boolean,
    onBack: () -> Unit,
    onFindInAccounts: () -> Unit,
    viewModel: AddDocumentViewModel = koinViewModel { parametersOf(expectedTypes, suggestedTitle, incoming) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val launcher = rememberDocumentCaptureLauncher(
        onCaptured = viewModel::onCaptured,
        onError = viewModel::onCaptureError,
    )

    LaunchedEffect(state.saved) {
        if (state.saved) onBack()
    }

    Scaffold(topBar = { AppTopBar("Add document", onBack = onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (expectedTypes.isNotEmpty()) {
                Text(
                    "Looking for: " + expectedTypes.joinToString(", ") { it.displayName },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = launcher::scan, enabled = !state.verifying && !state.saving) { Text("Scan") }
                OutlinedButton(onClick = launcher::pickFile, enabled = !state.verifying && !state.saving) {
                    Text("Choose file")
                }
                OutlinedButton(onClick = launcher::importFromDigiLocker, enabled = !state.verifying && !state.saving) {
                    Text("DigiLocker")
                }
            }
            if (state.file == null) {
                TextButton(onClick = onFindInAccounts) { Text("Find in email, drives or connected apps") }
            }

            val file = state.file
            if (file != null) {
                Text(
                    "${file.fileName} · ${formatSize(file.bytes.size.toLong())}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                SignatureSection(state)
                VerificationSection(state, onRetry = viewModel::verify)
                DocumentForm(state, viewModel)
                Button(onClick = viewModel::save, enabled = state.canSave, modifier = Modifier.fillMaxWidth()) {
                    val signed = state.signature?.status == SignatureStatus.VALID
                    Text(if (state.verification == null && !signed) "Save without verification" else "Save to vault")
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(
                "Files are sent to your AllTheDocs server only to verify them and aren't stored there. " +
                    "The vault copy is encrypted on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SignatureSection(state: AddDocumentUiState) {
    val signature = state.signature
    when {
        state.checkingSignature -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text("Checking digital signature…")
        }
        signature != null -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (signature.status) {
                SignatureStatus.VALID -> StatusChip("Digitally signed" + (signature.signerName?.let { " by $it" } ?: ""), Tone.Positive)
                SignatureStatus.UNTRUSTED -> StatusChip("Signed, but signer isn't a recognised authority", Tone.Warning)
                SignatureStatus.INVALID -> StatusChip("Signature invalid", Tone.Negative)
                SignatureStatus.NOT_SIGNED -> StatusChip("Not digitally signed", Tone.Neutral)
            }
            if (signature.message.isNotBlank() && signature.status != SignatureStatus.VALID) {
                Text(signature.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun VerificationSection(state: AddDocumentUiState, onRetry: () -> Unit) {
    when {
        state.verifying -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text("Verifying document…")
        }
        state.verificationError != null -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(state.verificationError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onRetry) { Text("Retry verification") }
        }
        state.verification != null -> VerificationCard(state.verification)
    }
}

@Composable
private fun VerificationCard(result: VerificationResult) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (result.matchesExpected) StatusChip("Right document", Tone.Positive) else StatusChip("Different document", Tone.Warning)
                when (result.nameMatches) {
                    true -> StatusChip("Name matches", Tone.Positive)
                    false -> StatusChip("Name mismatch", Tone.Negative)
                    null -> Unit
                }
            }
            Text(
                "Detected: ${result.detectedType.displayName} (${(result.confidence * 100).toInt()}% confidence)",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (result.summary.isNotBlank()) Text(result.summary, style = MaterialTheme.typography.bodySmall)
            result.issues.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocumentForm(state: AddDocumentUiState, viewModel: AddDocumentViewModel) {
    var typeMenuOpen by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = typeMenuOpen, onExpandedChange = { typeMenuOpen = it }) {
        OutlinedTextField(
            value = state.type.displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Document type") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeMenuOpen) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = typeMenuOpen, onDismissRequest = { typeMenuOpen = false }) {
            DocumentType.entries.forEach { type ->
                DropdownMenuItem(
                    text = { Text(type.displayName) },
                    onClick = {
                        viewModel.onTypeChange(type)
                        typeMenuOpen = false
                    },
                )
            }
        }
    }
    OutlinedTextField(
        value = state.title,
        onValueChange = viewModel::onTitleChange,
        label = { Text("Title") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.holderName,
        onValueChange = viewModel::onHolderNameChange,
        label = { Text("Holder name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.issuer,
        onValueChange = viewModel::onIssuerChange,
        label = { Text("Issued by") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.issueDate,
        onValueChange = viewModel::onIssueDateChange,
        label = { Text("Issue date (YYYY-MM-DD)") },
        isError = !state.issueDateValid,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.expiryDate,
        onValueChange = viewModel::onExpiryDateChange,
        label = { Text("Expiry date (YYYY-MM-DD)") },
        supportingText = { Text("You'll get reminders 30 and 7 days before it expires") },
        isError = !state.expiryDateValid,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
