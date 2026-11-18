package com.ashish.allthedocs.checklist

import com.ashish.allthedocs.api.DocumentRequirement
import com.ashish.allthedocs.model.DocumentType
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChecklistEvaluatorTest {
    private val today = LocalDate(2026, 10, 15)
    private val idProof = DocumentRequirement(
        id = "id_proof",
        name = "Identity proof",
        acceptedTypes = listOf(DocumentType.PASSPORT, DocumentType.AADHAAR),
    )

    private fun doc(id: String, type: DocumentType, expiry: LocalDate? = null, hasIssues: Boolean = false) =
        VaultDocumentRef(id, type, id, expiry, hasIssues)

    @Test
    fun missingWhenNoAcceptedTypeInVault() {
        val status = ChecklistEvaluator.evaluate(idProof, listOf(doc("pan", DocumentType.PAN_CARD)), today)
        assertEquals(RequirementState.MISSING, status.state)
        assertNull(status.matchedDocument)
    }

    @Test
    fun foundWhenValidDocumentPresent() {
        val status = ChecklistEvaluator.evaluate(idProof, listOf(doc("aadhaar", DocumentType.AADHAAR)), today)
        assertEquals(RequirementState.FOUND, status.state)
        assertEquals("aadhaar", status.matchedDocument?.id)
    }

    @Test
    fun needsActionWhenOnlyExpiredDocumentPresent() {
        val status = ChecklistEvaluator.evaluate(
            idProof,
            listOf(doc("passport", DocumentType.PASSPORT, expiry = LocalDate(2026, 1, 1))),
            today,
        )
        assertEquals(RequirementState.NEEDS_ACTION, status.state)
        assertEquals("Expired on 2026-01-01", status.reason)
    }

    @Test
    fun needsActionWhenExpiringWithinWarningWindow() {
        val status = ChecklistEvaluator.evaluate(
            idProof,
            listOf(doc("passport", DocumentType.PASSPORT, expiry = LocalDate(2026, 11, 1))),
            today,
        )
        assertEquals(RequirementState.NEEDS_ACTION, status.state)
    }

    @Test
    fun prefersValidDocumentOverExpiredOne() {
        val status = ChecklistEvaluator.evaluate(
            idProof,
            listOf(
                doc("old", DocumentType.PASSPORT, expiry = LocalDate(2025, 1, 1)),
                doc("aadhaar", DocumentType.AADHAAR),
            ),
            today,
        )
        assertEquals(RequirementState.FOUND, status.state)
        assertEquals("aadhaar", status.matchedDocument?.id)
    }

    @Test
    fun otherTypeNeverSatisfiesRequirement() {
        val requirement = idProof.copy(acceptedTypes = listOf(DocumentType.OTHER))
        val status = ChecklistEvaluator.evaluate(requirement, listOf(doc("x", DocumentType.OTHER)), today)
        assertEquals(RequirementState.MISSING, status.state)
    }
}
