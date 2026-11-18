package com.ashish.allthedocs.ui.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ashish.allthedocs.api.VerificationResult
import com.ashish.allthedocs.data.NewVaultDocument
import com.ashish.allthedocs.data.VaultDocument
import com.ashish.allthedocs.data.VaultRepository
import com.ashish.allthedocs.model.DocumentCategory
import com.ashish.allthedocs.api.SignatureCheckResult
import com.ashish.allthedocs.model.DocumentType
import com.ashish.allthedocs.model.DocumentTypeGuesser
import com.ashish.allthedocs.platform.IncomingDocuments
import com.ashish.allthedocs.model.VaultSection
import com.ashish.allthedocs.platform.CapturedFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

data class VaultFilters(
    val section: VaultSection = VaultSection.ISSUED,
    val query: String = "",
    val category: DocumentCategory? = null,
)

data class VaultListUiState(
    val documents: List<VaultDocument>? = null,
    val filters: VaultFilters = VaultFilters(),
) {
    fun count(section: VaultSection): Int = documents.orEmpty().count { it.section == section }

    val categories: List<DocumentCategory>
        get() = documents.orEmpty().filter { it.section == filters.section }.map { it.type.category }.distinct().sorted()

    val groups: List<Pair<DocumentCategory, List<VaultDocument>>>
        get() {
            val query = filters.query.trim()
            return documents.orEmpty()
                .filter { it.section == filters.section }
                .filter { filters.category == null || it.type.category == filters.category }
                .filter { doc ->
                    query.isEmpty() || listOfNotNull(doc.title, doc.type.displayName, doc.issuer, doc.holderName)
                        .any { it.contains(query, ignoreCase = true) }
                }
                .groupBy { it.type.category }
                .toList()
                .sortedBy { it.first }
        }
}

class VaultListViewModel(repository: VaultRepository) : ViewModel() {
    private val filters = MutableStateFlow(VaultFilters())

    val state: StateFlow<VaultListUiState> = combine(repository.documents, filters) { documents, filters ->
        VaultListUiState(documents, filters)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VaultListUiState())

    fun onSectionChange(section: VaultSection) = filters.update { it.copy(section = section, category = null) }

    fun onQueryChange(query: String) = filters.update { it.copy(query = query) }

    fun onCategoryChange(category: DocumentCategory?) = filters.update { it.copy(category = category) }
}

class VaultDocumentViewModel(
    private val documentId: String,
    private val repository: VaultRepository,
) : ViewModel() {
    val document: StateFlow<VaultDocument?> = repository.document(documentId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _verifying = MutableStateFlow(false)
    val verifying: StateFlow<Boolean> = _verifying.asStateFlow()

    private val _info = MutableStateFlow<String?>(null)
    val info: StateFlow<String?> = _info.asStateFlow()

    fun open() = launchCatching { repository.open(documentId) }

    fun reverify() {
        if (_verifying.value) return
        _verifying.value = true
        _error.update { null }
        _info.update { null }
        launchCatching {
            try {
                val outcome = repository.reverify(documentId)
                _info.update { outcome.aiError?.let { "Digital signature checked. AI verification unavailable: $it" } }
            } finally {
                _verifying.value = false
            }
        }
    }

    fun delete(onDeleted: () -> Unit) = launchCatching {
        repository.delete(documentId)
        onDeleted()
    }

    fun dismissError() = _error.update { null }

    private fun launchCatching(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.update { e.message ?: "Something went wrong" }
            }
        }
    }
}

data class AddDocumentUiState(
    val file: CapturedFile? = null,
    val verifying: Boolean = false,
    val verification: VerificationResult? = null,
    val verificationError: String? = null,
    val checkingSignature: Boolean = false,
    val signature: SignatureCheckResult? = null,
    val type: DocumentType = DocumentType.OTHER,
    val title: String = "",
    val holderName: String = "",
    val issuer: String = "",
    val issueDate: String = "",
    val expiryDate: String = "",
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
) {
    val issueDateValid: Boolean get() = issueDate.isBlank() || parseDate(issueDate) != null
    val expiryDateValid: Boolean get() = expiryDate.isBlank() || parseDate(expiryDate) != null
    val canSave: Boolean
        get() = file != null && !verifying && !checkingSignature && !saving && issueDateValid && expiryDateValid
}

internal fun parseDate(value: String): LocalDate? = runCatching { LocalDate.parse(value.trim()) }.getOrNull()

class AddDocumentViewModel(
    private val expectedTypes: List<DocumentType>,
    suggestedTitle: String,
    incoming: Boolean,
    private val repository: VaultRepository,
    incomingDocuments: IncomingDocuments,
) : ViewModel() {
    private val _state = MutableStateFlow(
        AddDocumentUiState(
            type = expectedTypes.firstOrNull() ?: DocumentType.OTHER,
            title = suggestedTitle,
        ),
    )
    val state: StateFlow<AddDocumentUiState> = _state.asStateFlow()

    init {
        if (incoming) incomingDocuments.take()?.let(::onCaptured)
    }

    fun onCaptured(file: CapturedFile) {
        _state.update { current ->
            val guessed = DocumentTypeGuesser.fromFileName(file.fileName)
                ?.takeIf { expectedTypes.isEmpty() || it in expectedTypes }
            current.copy(
                file = file,
                verification = null,
                verificationError = null,
                signature = null,
                error = null,
                type = guessed ?: current.type,
                title = current.title.ifBlank { guessed?.displayName.orEmpty() },
            )
        }
        checkSignature(file)
        verify()
    }

    private fun checkSignature(file: CapturedFile) {
        if (file.mimeType != "application/pdf") return
        _state.update { it.copy(checkingSignature = true) }
        viewModelScope.launch {
            try {
                val result = repository.checkSignature(file)
                _state.update { it.copy(signature = result) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(signature = null) }
            } finally {
                _state.update { it.copy(checkingSignature = false) }
            }
        }
    }

    fun onCaptureError(message: String) = _state.update { it.copy(error = message) }

    fun verify() {
        val file = _state.value.file ?: return
        _state.update { it.copy(verifying = true, verificationError = null) }
        viewModelScope.launch {
            try {
                val result = repository.verify(file, expectedTypes)
                _state.update { current -> current.prefillFrom(result) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(verificationError = e.message ?: "Verification failed") }
            } finally {
                _state.update { it.copy(verifying = false) }
            }
        }
    }

    fun onTypeChange(type: DocumentType) = _state.update { it.copy(type = type) }
    fun onTitleChange(value: String) = _state.update { it.copy(title = value) }
    fun onHolderNameChange(value: String) = _state.update { it.copy(holderName = value) }
    fun onIssuerChange(value: String) = _state.update { it.copy(issuer = value) }
    fun onIssueDateChange(value: String) = _state.update { it.copy(issueDate = value) }
    fun onExpiryDateChange(value: String) = _state.update { it.copy(expiryDate = value) }

    fun save() {
        val current = _state.value
        val file = current.file ?: return
        if (!current.canSave) return
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                repository.save(
                    NewVaultDocument(
                        file = file,
                        type = current.type,
                        title = current.title.trim(),
                        holderName = current.holderName.trim(),
                        issuer = current.issuer.trim(),
                        issueDate = parseDate(current.issueDate),
                        expiryDate = parseDate(current.expiryDate),
                        verification = current.verification,
                        signature = current.signature,
                    ),
                )
                _state.update { it.copy(saved = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Couldn't save the document") }
            } finally {
                _state.update { it.copy(saving = false) }
            }
        }
    }

    private fun AddDocumentUiState.prefillFrom(result: VerificationResult): AddDocumentUiState {
        val detected = result.detectedType.takeIf { it != DocumentType.OTHER } ?: type
        return copy(
            verification = result,
            type = detected,
            title = title.ifBlank { detected.displayName },
            holderName = holderName.ifBlank { result.holderName.orEmpty() },
            issuer = issuer.ifBlank { result.issuingAuthority.orEmpty() },
            issueDate = issueDate.ifBlank { result.issueDate?.toString().orEmpty() },
            expiryDate = expiryDate.ifBlank { result.expiryDate?.toString().orEmpty() },
        )
    }
}
