package com.ashish.allthedocs.data

import com.ashish.allthedocs.api.ApiJson
import com.ashish.allthedocs.api.ApiRoutes
import com.ashish.allthedocs.api.VerificationRequest
import com.ashish.allthedocs.api.VerificationResult
import com.ashish.allthedocs.checklist.VaultDocumentRef
import com.ashish.allthedocs.data.db.VaultDocumentDao
import com.ashish.allthedocs.data.db.VaultDocumentEntity
import com.ashish.allthedocs.api.SignatureCheckResult
import com.ashish.allthedocs.api.SignatureStatus
import com.ashish.allthedocs.model.DocumentSource
import com.ashish.allthedocs.model.DocumentType
import com.ashish.allthedocs.model.VaultSection
import com.ashish.allthedocs.platform.CapturedFile
import com.ashish.allthedocs.platform.DocumentViewer
import com.ashish.allthedocs.platform.ExpiryReminderScheduler
import com.ashish.allthedocs.platform.SecureFileStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class VaultDocument(
    val id: String,
    val type: DocumentType,
    val title: String,
    val holderName: String?,
    val issueDate: LocalDate?,
    val expiryDate: LocalDate?,
    val mimeType: String,
    val originalFileName: String,
    val sizeBytes: Long,
    val addedAtEpochMs: Long,
    val verified: Boolean,
    val verificationSummary: String?,
    val issues: List<String>,
    val issuer: String?,
    val source: DocumentSource?,
    val signatureStatus: SignatureStatus?,
    val signedBy: String?,
) {
    val section: VaultSection
        get() = VaultSection.of(type, verified, issues.isNotEmpty(), signatureStatus == SignatureStatus.VALID)

    fun toRef() = VaultDocumentRef(id, type, title, expiryDate, hasIssues = issues.isNotEmpty())
}

data class NewVaultDocument(
    val file: CapturedFile,
    val type: DocumentType,
    val title: String,
    val holderName: String?,
    val issueDate: LocalDate?,
    val expiryDate: LocalDate?,
    val issuer: String?,
    val verification: VerificationResult?,
    val signature: SignatureCheckResult?,
)

data class ReverifyOutcome(
    val verification: VerificationResult?,
    val signature: SignatureCheckResult?,
    val aiError: String?,
)

class VaultRepository(
    private val dao: VaultDocumentDao,
    private val api: ApiClient,
    private val settings: SettingsRepository,
    private val fileStore: SecureFileStore,
    private val reminders: ExpiryReminderScheduler,
    private val viewer: DocumentViewer,
) {
    val documents: Flow<List<VaultDocument>> = dao.observeAll().map { list -> list.map { it.toDocument() } }

    fun document(id: String): Flow<VaultDocument?> = dao.observe(id).map { it?.toDocument() }

    suspend fun verify(file: CapturedFile, expectedTypes: List<DocumentType>): VerificationResult {
        if (file.bytes.size > ApiRoutes.MAX_UPLOAD_BYTES) {
            throw ApiException("File is larger than ${ApiRoutes.MAX_UPLOAD_BYTES / (1024 * 1024)} MB.")
        }
        return api.verify(
            VerificationRequest(
                fileName = file.fileName,
                mimeType = file.mimeType,
                expectedTypes = expectedTypes,
                expectedHolderName = settings.holderName(),
            ),
            file.bytes,
        )
    }

    suspend fun checkSignature(file: CapturedFile): SignatureCheckResult? {
        if (file.mimeType != PDF_MIME_TYPE) return null
        return api.checkSignature(file.fileName, file.mimeType, file.bytes)
    }

    @OptIn(ExperimentalUuidApi::class, ExperimentalTime::class)
    suspend fun save(document: NewVaultDocument): String {
        val id = Uuid.random().toString()
        val storedFileName = "$id.bin"
        fileStore.write(storedFileName, document.file.bytes)
        val verification = document.verification
        dao.upsert(
            VaultDocumentEntity(
                id = id,
                type = document.type.key,
                title = document.title.ifBlank { document.type.displayName },
                holderName = document.holderName?.takeIf { it.isNotBlank() },
                issueDate = document.issueDate?.toString(),
                expiryDate = document.expiryDate?.toString(),
                mimeType = document.file.mimeType,
                originalFileName = document.file.fileName,
                storedFileName = storedFileName,
                sizeBytes = document.file.bytes.size.toLong(),
                addedAtEpochMs = Clock.System.now().toEpochMilliseconds(),
                verified = verification != null,
                verificationSummary = verification?.summary?.takeIf { it.isNotBlank() },
                issuesJson = ApiJson.encodeToString(stringsSerializer, issuesOf(verification, document.signature)),
                issuer = document.issuer?.takeIf { it.isNotBlank() },
                source = document.file.source.name,
                signatureStatus = document.signature?.status?.name,
                signedBy = document.signature?.signerName,
            ),
        )
        document.expiryDate?.let { reminders.schedule(id, document.title.ifBlank { document.type.displayName }, it) }
        return id
    }

    suspend fun reverify(id: String): ReverifyOutcome {
        val entity = dao.get(id) ?: throw ApiException("Document not found")
        val type = DocumentType.fromKey(entity.type)
        val bytes = fileStore.read(entity.storedFileName)
        val file = CapturedFile(bytes, entity.mimeType, entity.originalFileName)
        val signature = runCatching { checkSignature(file) }.getOrNull()
        var aiError: String? = null
        val result = try {
            verify(file, listOf(type))
        } catch (e: ApiException) {
            if (signature == null) throw e
            aiError = e.message
            null
        }
        val issues = if (result != null) {
            issuesOf(result, signature)
        } else {
            (decodeOrEmpty(stringsSerializer, entity.issuesJson) + issuesOf(null, signature)).distinct()
        }
        val newExpiry = entity.expiryDate ?: result?.expiryDate?.toString()
        dao.upsert(
            entity.copy(
                holderName = entity.holderName ?: result?.holderName,
                issueDate = entity.issueDate ?: result?.issueDate?.toString(),
                expiryDate = newExpiry,
                verified = entity.verified || result != null,
                verificationSummary = result?.summary?.takeIf { it.isNotBlank() } ?: entity.verificationSummary,
                issuesJson = ApiJson.encodeToString(stringsSerializer, issues),
                issuer = entity.issuer ?: result?.issuingAuthority,
                signatureStatus = signature?.status?.name ?: entity.signatureStatus,
                signedBy = signature?.signerName ?: entity.signedBy,
            ),
        )
        if (entity.expiryDate == null && newExpiry != null) {
            reminders.schedule(id, entity.title, LocalDate.parse(newExpiry))
        }
        return ReverifyOutcome(result, signature, aiError)
    }

    suspend fun delete(id: String) {
        val entity = dao.get(id) ?: return
        reminders.cancel(id)
        fileStore.delete(entity.storedFileName)
        dao.delete(id)
    }

    suspend fun open(id: String) {
        val entity = dao.get(id) ?: return
        viewer.open(fileStore.read(entity.storedFileName), entity.mimeType, entity.originalFileName)
    }

    private fun VaultDocumentEntity.toDocument() = VaultDocument(
        id = id,
        type = DocumentType.fromKey(type),
        title = title,
        holderName = holderName,
        issueDate = issueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        expiryDate = expiryDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        mimeType = mimeType,
        originalFileName = originalFileName,
        sizeBytes = sizeBytes,
        addedAtEpochMs = addedAtEpochMs,
        verified = verified,
        verificationSummary = verificationSummary,
        issues = decodeOrEmpty(stringsSerializer, issuesJson),
        issuer = issuer,
        source = DocumentSource.entries.firstOrNull { it.name == source },
        signatureStatus = SignatureStatus.entries.firstOrNull { it.name == signatureStatus },
        signedBy = signedBy,
    )

    private fun issuesOf(verification: VerificationResult?, signature: SignatureCheckResult?): List<String> =
        verification?.issues.orEmpty() + listOfNotNull(
            signature?.takeIf { it.status == SignatureStatus.INVALID }?.message?.ifBlank { "Digital signature is invalid" },
        )

    private companion object {
        const val PDF_MIME_TYPE = "application/pdf"
        val stringsSerializer = ListSerializer(String.serializer())
    }
}
