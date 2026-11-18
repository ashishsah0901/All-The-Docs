package com.ashish.allthedocs.checklist

import com.ashish.allthedocs.api.DocumentRequirement
import com.ashish.allthedocs.model.DocumentType
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

data class VaultDocumentRef(
    val id: String,
    val type: DocumentType,
    val title: String,
    val expiryDate: LocalDate?,
    val hasIssues: Boolean,
)

enum class RequirementState { FOUND, NEEDS_ACTION, MISSING }

data class RequirementStatus(
    val requirement: DocumentRequirement,
    val state: RequirementState,
    val matchedDocument: VaultDocumentRef?,
    val reason: String?,
)

object ChecklistEvaluator {
    const val EXPIRY_WARNING_DAYS = 30

    fun evaluate(
        requirements: List<DocumentRequirement>,
        vault: List<VaultDocumentRef>,
        today: LocalDate,
    ): List<RequirementStatus> = requirements.map { evaluate(it, vault, today) }

    fun evaluate(
        requirement: DocumentRequirement,
        vault: List<VaultDocumentRef>,
        today: LocalDate,
    ): RequirementStatus {
        val candidates = vault.filter { it.type in requirement.acceptedTypes && it.type != DocumentType.OTHER }
        if (candidates.isEmpty()) {
            return RequirementStatus(requirement, RequirementState.MISSING, null, null)
        }
        val ranked = candidates.sortedWith(
            compareBy<VaultDocumentRef> { problemOf(it, today) != null }
                .thenByDescending { it.expiryDate ?: LocalDate.fromEpochDays(Int.MAX_VALUE / 2) },
        )
        val best = ranked.first()
        val problem = problemOf(best, today)
        return RequirementStatus(
            requirement = requirement,
            state = if (problem == null) RequirementState.FOUND else RequirementState.NEEDS_ACTION,
            matchedDocument = best,
            reason = problem,
        )
    }

    private fun problemOf(doc: VaultDocumentRef, today: LocalDate): String? {
        val expiry = doc.expiryDate
        return when {
            expiry != null && expiry < today -> "Expired on $expiry"
            expiry != null && expiry <= today.plus(DatePeriod(days = EXPIRY_WARNING_DAYS)) -> "Expires on $expiry"
            doc.hasIssues -> "Verification flagged issues"
            else -> null
        }
    }
}
