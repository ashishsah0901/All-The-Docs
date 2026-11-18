package com.ashish.allthedocs.api

import com.ashish.allthedocs.model.DocumentType
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object ApiRoutes {
    const val HEALTH = "/api/v1/health"
    const val ANALYZE_SCENARIO = "/api/v1/scenario/analyze"
    const val RESOLVE_REQUIREMENTS = "/api/v1/scenario/requirements"
    const val VERIFY_DOCUMENT = "/api/v1/documents/verify"
    const val CURATED_SCENARIOS = "/api/v1/scenario/curated"
    const val CHECK_SIGNATURE = "/api/v1/documents/signature"

    const val VERIFY_PART_METADATA = "metadata"
    const val VERIFY_PART_FILE = "file"
    const val MAX_UPLOAD_BYTES = 10L * 1024 * 1024
}

@Serializable
data class AnalyzeScenarioRequest(val description: String)

@Serializable
data class AnalyzeScenarioResponse(
    val title: String,
    val countryCode: String? = null,
    val curatedScenarioId: String? = null,
    val questions: List<FollowUpQuestion> = emptyList(),
)

@Serializable
data class CuratedScenarioSummary(
    val id: String,
    val title: String,
    val summary: String,
    val countryCode: String,
    val questions: List<FollowUpQuestion> = emptyList(),
) {
    fun toAnalysis() = AnalyzeScenarioResponse(title, countryCode, id, questions)
}

@Serializable
data class CuratedScenariosResponse(val scenarios: List<CuratedScenarioSummary>)

@Serializable
data class FollowUpQuestion(
    val id: String,
    val text: String,
    val options: List<String> = emptyList(),
    val allowFreeText: Boolean = options.isEmpty(),
)

@Serializable
data class FollowUpAnswer(
    val questionId: String,
    val question: String,
    val answer: String,
)

@Serializable
data class ResolveRequirementsRequest(
    val description: String,
    val title: String,
    val countryCode: String? = null,
    val curatedScenarioId: String? = null,
    val answers: List<FollowUpAnswer> = emptyList(),
)

@Serializable
data class ResolveRequirementsResponse(
    val title: String,
    val countryCode: String? = null,
    val origin: RequirementOrigin,
    val requirements: List<DocumentRequirement>,
    val assumptions: List<String> = emptyList(),
)

@Serializable
enum class RequirementOrigin { CURATED, LLM_WEB_SEARCH, LLM_KNOWLEDGE }

@Serializable
data class DocumentRequirement(
    val id: String,
    val name: String,
    val description: String = "",
    val acceptedTypes: List<DocumentType> = listOf(DocumentType.OTHER),
    val mandatory: Boolean = true,
    val specs: List<String> = emptyList(),
    val obtainGuide: ObtainGuide? = null,
    val citations: List<SourceLink> = emptyList(),
) {
    val isCited: Boolean get() = citations.isNotEmpty()
}

@Serializable
data class ObtainGuide(
    val steps: List<String> = emptyList(),
    val links: List<SourceLink> = emptyList(),
    val typicalFee: String? = null,
    val typicalTime: String? = null,
)

@Serializable
data class SourceLink(val title: String, val url: String)

@Serializable
data class VerificationRequest(
    val fileName: String,
    val mimeType: String,
    val expectedTypes: List<DocumentType> = emptyList(),
    val expectedHolderName: String? = null,
)

@Serializable
data class VerificationResult(
    val detectedType: DocumentType,
    val matchesExpected: Boolean,
    val holderName: String? = null,
    val nameMatches: Boolean? = null,
    val issuingAuthority: String? = null,
    val issueDate: LocalDate? = null,
    val expiryDate: LocalDate? = null,
    val confidence: Double = 0.0,
    val issues: List<String> = emptyList(),
    val summary: String = "",
)

@Serializable
enum class SignatureStatus { VALID, UNTRUSTED, INVALID, NOT_SIGNED }

@Serializable
data class SignatureCheckResult(
    val status: SignatureStatus,
    val signerName: String? = null,
    val signedAt: String? = null,
    val message: String = "",
)

@Serializable
data class ApiError(val message: String)

val ApiJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
    isLenient = true
}
