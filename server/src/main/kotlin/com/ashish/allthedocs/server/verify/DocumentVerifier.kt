package com.ashish.allthedocs.server.verify

import com.ashish.allthedocs.api.ApiJson
import com.ashish.allthedocs.api.VerificationRequest
import com.ashish.allthedocs.api.VerificationResult
import com.ashish.allthedocs.model.DocumentType
import com.ashish.allthedocs.server.ApiException
import com.ashish.allthedocs.server.LlmOutputException
import com.ashish.allthedocs.server.LlmUnavailableException
import com.ashish.allthedocs.server.llm.JsonExtraction
import com.ashish.allthedocs.server.llm.LlmProvider
import com.ashish.allthedocs.server.llm.LlmRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable

@Serializable
internal data class LlmVerification(
    val documentType: DocumentType = DocumentType.OTHER,
    val holderName: String? = null,
    val issuingAuthority: String? = null,
    val issueDate: String? = null,
    val expiryDate: String? = null,
    val confidence: Double = 0.0,
    val issues: List<String> = emptyList(),
    val summary: String = "",
)

class DocumentVerifier(
    private val llm: LlmProvider?,
    private val today: () -> LocalDate,
) {
    suspend fun verify(request: VerificationRequest, bytes: ByteArray): VerificationResult {
        val rendered = withContext(Dispatchers.IO) { DocumentRenderer.render(bytes) }
        val provider = llm ?: throw LlmUnavailableException(
            "No LLM provider is configured, so documents can't be verified. Set GROQ_API_KEY on the server.",
        )
        val content = try {
            provider.complete(
                LlmRequest(
                    systemPrompt = SYSTEM_PROMPT,
                    userText = userText(request, rendered.text),
                    images = rendered.pageImages,
                    jsonMode = true,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw LlmUnavailableException("LLM call failed: ${e::class.simpleName}")
        }
        val parsed = try {
            ApiJson.decodeFromJsonElement(LlmVerification.serializer(), JsonExtraction.extractJsonObject(content))
        } catch (e: SerializationException) {
            throw LlmOutputException("LLM verification response had an unexpected shape")
        } catch (e: IllegalArgumentException) {
            throw LlmOutputException("LLM verification response had an unexpected shape")
        }
        return toResult(request, parsed)
    }

    internal fun toResult(request: VerificationRequest, parsed: LlmVerification): VerificationResult {
        val expiry = parseDate(parsed.expiryDate)
        val issues = parsed.issues.map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        val matchesExpected = request.expectedTypes.isEmpty() || parsed.documentType in request.expectedTypes
        if (!matchesExpected) {
            issues += "Looks like a ${parsed.documentType.displayName}, not ${request.expectedTypes.joinToString(" / ") { it.displayName }}"
        }
        val holderName = parsed.holderName?.trim()?.takeIf { it.isNotEmpty() }
        val nameMatches = NameMatcher.matches(request.expectedHolderName, holderName)
        if (nameMatches == false) issues += "Name on the document ($holderName) doesn't match your profile name"
        if (expiry != null && expiry < today()) issues += "Document expired on $expiry"
        return VerificationResult(
            detectedType = parsed.documentType,
            matchesExpected = matchesExpected,
            holderName = holderName,
            nameMatches = nameMatches,
            issuingAuthority = parsed.issuingAuthority?.trim()?.takeIf { it.isNotEmpty() }?.take(120),
            issueDate = parseDate(parsed.issueDate),
            expiryDate = expiry,
            confidence = parsed.confidence.coerceIn(0.0, 1.0),
            issues = issues.distinct(),
            summary = parsed.summary.trim(),
        )
    }

    private fun parseDate(raw: String?): LocalDate? =
        raw?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }

    private fun userText(request: VerificationRequest, extractedText: String?): String = buildString {
        if (request.expectedTypes.isNotEmpty()) {
            appendLine("The user says this should be: ${request.expectedTypes.joinToString { it.key }}.")
        }
        appendLine("File name: ${request.fileName.take(120)}")
        if (extractedText != null) {
            appendLine("Text extracted from the PDF (may be partial):")
            appendLine(extractedText)
        }
        appendLine("The attached images are the document's pages.")
    }

    companion object {
        private val SYSTEM_PROMPT = buildString {
            appendLine("You classify and check a single personal or official document from its page images.")
            appendLine("Reply with ONLY a JSON object of this shape:")
            appendLine("""{"documentType": string, "holderName": string|null, "issuingAuthority": string|null, "issueDate": "YYYY-MM-DD"|null, "expiryDate": "YYYY-MM-DD"|null, "confidence": number, "issues": [string], "summary": string}""")
            appendLine("- documentType: one of ${DocumentType.entries.joinToString { it.key }}.")
            appendLine("- holderName: the name of the person the document belongs to, exactly as printed, or null.")
            appendLine("- issuingAuthority: the organisation that issued the document as printed on it (e.g. a government department, bank, employer or university), or null.")
            appendLine("- expiryDate: the validity end date if the document has one, else null. Dates must be ISO YYYY-MM-DD.")
            appendLine("- confidence: 0 to 1 for the documentType classification.")
            appendLine("- issues: problems such as unreadable, cropped, blurred, partially hidden, looks edited, or missing pages.")
            appendLine("- summary: one short sentence describing the document, WITHOUT repeating ID numbers or other sensitive numbers.")
        }
    }
}
