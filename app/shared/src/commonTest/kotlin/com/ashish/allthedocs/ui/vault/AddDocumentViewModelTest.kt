package com.ashish.allthedocs.ui.vault

import com.ashish.allthedocs.api.ApiError
import com.ashish.allthedocs.api.ApiJson
import com.ashish.allthedocs.api.ApiRoutes
import com.ashish.allthedocs.api.SignatureCheckResult
import com.ashish.allthedocs.api.SignatureStatus
import com.ashish.allthedocs.data.ApiClient
import com.ashish.allthedocs.data.SettingsRepository
import com.ashish.allthedocs.data.VaultRepository
import com.ashish.allthedocs.data.db.SettingDao
import com.ashish.allthedocs.data.db.SettingEntity
import com.ashish.allthedocs.data.db.VaultDocumentDao
import com.ashish.allthedocs.data.db.VaultDocumentEntity
import com.ashish.allthedocs.model.DocumentSource
import com.ashish.allthedocs.model.DocumentType
import com.ashish.allthedocs.model.VaultSection
import com.ashish.allthedocs.platform.CapturedFile
import com.ashish.allthedocs.platform.DocumentViewer
import com.ashish.allthedocs.platform.ExpiryReminderScheduler
import com.ashish.allthedocs.platform.IncomingDocuments
import com.ashish.allthedocs.platform.SecureFileStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class AddDocumentViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val dao = FakeVaultDao()
    private val incoming = IncomingDocuments()
    private val signedPdf = "%PDF-1.7 signed".encodeToByteArray()

    private val api = ApiClient(
        ApiClient.createHttpClient(
            MockEngine { request ->
                val headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                when (request.url.encodedPath) {
                    ApiRoutes.CHECK_SIGNATURE -> respond(
                        ApiJson.encodeToString(
                            SignatureCheckResult.serializer(),
                            SignatureCheckResult(SignatureStatus.VALID, "DS Income Tax Department", "2026-01-01T00:00:00Z"),
                        ),
                        HttpStatusCode.OK,
                        headers,
                    )
                    else -> respond(
                        ApiJson.encodeToString(ApiError.serializer(), ApiError("No LLM provider is configured")),
                        HttpStatusCode.ServiceUnavailable,
                        headers,
                    )
                }
            },
        ),
    ) { "http://server.test" }

    private val repository = VaultRepository(
        dao,
        api,
        SettingsRepository(FakeSettingDao()),
        FakeFileStore(),
        NoopReminders(),
        NoopViewer(),
    )

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun sharedSignedDigiLockerPdfIsSavedAsIssuedWithoutAi() = runTest(dispatcher) {
        incoming.offer(CapturedFile(signedPdf, "application/pdf", "PANCR-XXXXX0000X.pdf", DocumentSource.SHARED))
        val viewModel = AddDocumentViewModel(emptyList(), "", incoming = true, repository, incoming)

        val ready = viewModel.state.first { !it.verifying && !it.checkingSignature && it.signature != null }

        assertNull(incoming.pending.value)
        assertEquals(DocumentType.PAN_CARD, ready.type)
        assertEquals(SignatureStatus.VALID, ready.signature?.status)
        assertEquals("No LLM provider is configured", ready.verificationError)

        viewModel.save()
        viewModel.state.first { it.saved }

        val saved = repository.documents.first().single()
        assertEquals(DocumentSource.SHARED, saved.source)
        assertEquals("DS Income Tax Department", saved.signedBy)
        assertEquals(VaultSection.ISSUED, saved.section)
    }

    @Test
    fun reverifyPromotesSignedDocumentWhenAiIsUnavailable() = runTest(dispatcher) {
        val id = repository.save(
            com.ashish.allthedocs.data.NewVaultDocument(
                file = CapturedFile(signedPdf, "application/pdf", "pan.pdf", DocumentSource.FILE),
                type = DocumentType.PAN_CARD,
                title = "PAN",
                holderName = null,
                issueDate = null,
                expiryDate = null,
                issuer = null,
                verification = null,
                signature = null,
            ),
        )
        assertEquals(VaultSection.UPLOADED, repository.documents.first().single().section)

        val outcome = repository.reverify(id)

        assertEquals("No LLM provider is configured", outcome.aiError)
        assertEquals(VaultSection.ISSUED, repository.documents.first().single().section)
    }
}

private class FakeVaultDao : VaultDocumentDao {
    private val items = MutableStateFlow<Map<String, VaultDocumentEntity>>(emptyMap())
    override fun observeAll(): Flow<List<VaultDocumentEntity>> = items.map { it.values.toList() }
    override fun observe(id: String): Flow<VaultDocumentEntity?> = items.map { it[id] }
    override suspend fun get(id: String): VaultDocumentEntity? = items.value[id]
    override suspend fun upsert(entity: VaultDocumentEntity) {
        items.value = items.value + (entity.id to entity)
    }
    override suspend fun delete(id: String) {
        items.value = items.value - id
    }
}

private class FakeSettingDao : SettingDao {
    private val items = MutableStateFlow<Map<String, String>>(emptyMap())
    override fun observe(key: String): Flow<String?> = items.map { it[key] }
    override suspend fun get(key: String): String? = items.value[key]
    override suspend fun upsert(entity: SettingEntity) {
        items.value = items.value + (entity.key to entity.value)
    }
}

private class FakeFileStore : SecureFileStore {
    private val files = mutableMapOf<String, ByteArray>()
    override suspend fun write(name: String, bytes: ByteArray) {
        files[name] = bytes
    }
    override suspend fun read(name: String): ByteArray = files.getValue(name)
    override suspend fun delete(name: String) {
        files.remove(name)
    }
}

private class NoopReminders : ExpiryReminderScheduler {
    override suspend fun schedule(documentId: String, title: String, expiryDate: LocalDate) = Unit
    override suspend fun cancel(documentId: String) = Unit
}

private class NoopViewer : DocumentViewer {
    override suspend fun open(bytes: ByteArray, mimeType: String, fileName: String) = Unit
}
