package com.ashish.allthedocs.ui.vault

import com.ashish.allthedocs.data.VaultDocument
import com.ashish.allthedocs.model.DocumentCategory
import com.ashish.allthedocs.model.DocumentType
import com.ashish.allthedocs.model.VaultSection
import kotlin.test.Test
import kotlin.test.assertEquals

class VaultListUiStateTest {
    private fun doc(
        id: String,
        type: DocumentType,
        verified: Boolean = true,
        issues: List<String> = emptyList(),
        issuer: String? = null,
    ) = VaultDocument(
        id = id,
        type = type,
        title = id,
        holderName = null,
        issueDate = null,
        expiryDate = null,
        mimeType = "application/pdf",
        originalFileName = "$id.pdf",
        sizeBytes = 1,
        addedAtEpochMs = 0,
        verified = verified,
        verificationSummary = null,
        issues = issues,
        issuer = issuer,
        source = null,
        signatureStatus = null,
        signedBy = null,
    )

    private val documents = listOf(
        doc("pan", DocumentType.PAN_CARD, issuer = "Income Tax Department"),
        doc("passport", DocumentType.PASSPORT),
        doc("payslip", DocumentType.SALARY_SLIP),
        doc("unverified-aadhaar", DocumentType.AADHAAR, verified = false),
        doc("cheque", DocumentType.CANCELLED_CHEQUE),
        doc("flagged-dl", DocumentType.DRIVING_LICENCE, issues = listOf("blurred")),
    )

    @Test
    fun countsSplitIssuedAndUploaded() {
        val state = VaultListUiState(documents)
        assertEquals(3, state.count(VaultSection.ISSUED))
        assertEquals(3, state.count(VaultSection.UPLOADED))
    }

    @Test
    fun groupsBySectionAndCategoryInCategoryOrder() {
        val state = VaultListUiState(documents)
        assertEquals(
            listOf(DocumentCategory.IDENTITY to listOf("pan", "passport"), DocumentCategory.EMPLOYMENT to listOf("payslip")),
            state.groups.map { (category, docs) -> category to docs.map { it.id } },
        )
    }

    @Test
    fun searchMatchesIssuer() {
        val state = VaultListUiState(documents, VaultFilters(query = "income tax"))
        assertEquals(listOf("pan"), state.groups.flatMap { it.second }.map { it.id })
    }

    @Test
    fun categoryFilterAppliesWithinSection() {
        val state = VaultListUiState(documents, VaultFilters(section = VaultSection.UPLOADED, category = DocumentCategory.IDENTITY))
        assertEquals(listOf("unverified-aadhaar", "flagged-dl"), state.groups.flatMap { it.second }.map { it.id })
        assertEquals(listOf(DocumentCategory.IDENTITY, DocumentCategory.FINANCIAL), VaultListUiState(documents, VaultFilters(section = VaultSection.UPLOADED)).categories)
    }
}
