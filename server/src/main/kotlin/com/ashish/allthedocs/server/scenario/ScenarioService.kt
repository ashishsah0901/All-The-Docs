package com.ashish.allthedocs.server.scenario

import com.ashish.allthedocs.api.AnalyzeScenarioRequest
import com.ashish.allthedocs.api.AnalyzeScenarioResponse
import com.ashish.allthedocs.api.ApiJson
import com.ashish.allthedocs.api.CuratedScenarioSummary
import com.ashish.allthedocs.api.CuratedScenariosResponse
import com.ashish.allthedocs.api.RequirementOrigin
import com.ashish.allthedocs.api.ResolveRequirementsRequest
import com.ashish.allthedocs.api.ResolveRequirementsResponse
import com.ashish.allthedocs.model.DocumentType
import com.ashish.allthedocs.server.ApiException
import com.ashish.allthedocs.server.InvalidInputException
import com.ashish.allthedocs.server.LlmOutputException
import com.ashish.allthedocs.server.LlmUnavailableException
import com.ashish.allthedocs.server.llm.JsonExtraction
import com.ashish.allthedocs.server.llm.LlmProvider
import com.ashish.allthedocs.server.llm.LlmRequest
import com.ashish.allthedocs.server.rules.CuratedScenario
import com.ashish.allthedocs.server.rules.CuratedScenarioRepository
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory

class ScenarioService(
    private val llm: LlmProvider?,
    private val curated: CuratedScenarioRepository,
) {
    private val log = LoggerFactory.getLogger(ScenarioService::class.java)

    fun curatedScenarios(): CuratedScenariosResponse = CuratedScenariosResponse(
        curated.scenarios.map { scenario ->
            CuratedScenarioSummary(
                id = scenario.id,
                title = scenario.title,
                summary = scenario.summary,
                countryCode = scenario.countryCode,
                questions = scenario.questions,
            )
        },
    )

    suspend fun analyze(request: AnalyzeScenarioRequest): AnalyzeScenarioResponse {
        val description = validDescription(request.description)
        if (llm == null) {
            val match = curated.matchByKeywords(description)
                ?: throw LlmUnavailableException(
                    "No LLM provider is configured and the scenario doesn't match a curated scenario. Set GROQ_API_KEY on the server.",
                )
            return curatedResponse(match)
        }
        val analysis = try {
            callForJson(
                LlmRequest(systemPrompt = analyzeSystemPrompt(), userText = "Scenario:\n$description"),
                LlmAnalysisPayload.serializer(),
            )
        } catch (e: ApiException) {
            val match = curated.matchByKeywords(description) ?: throw e
            log.warn("Scenario analysis via LLM failed ({}), using keyword match", e.message)
            return curatedResponse(match)
        }
        val countryCode = RequirementSanitizer.countryCode(analysis.countryCode)
        val match = curated.find(analysis.curatedScenarioId)?.takeIf { it.countryCode == countryCode }
        if (match != null) return curatedResponse(match)
        return AnalyzeScenarioResponse(
            title = analysis.title.trim().ifBlank { description.take(60) },
            countryCode = countryCode,
            curatedScenarioId = null,
            questions = RequirementSanitizer.questions(analysis.questions),
        )
    }

    suspend fun resolve(request: ResolveRequirementsRequest): ResolveRequirementsResponse {
        val description = validDescription(request.description)
        request.curatedScenarioId?.takeIf { it.isNotBlank() }?.let { id ->
            val scenario = curated.find(id) ?: throw InvalidInputException("Unknown curated scenario: $id")
            val answers = request.answers.associate { it.questionId to it.answer }
            return ResolveRequirementsResponse(
                title = scenario.title,
                countryCode = scenario.countryCode,
                origin = RequirementOrigin.CURATED,
                requirements = scenario.requirementsFor(answers),
                assumptions = scenario.assumptions,
            )
        }
        if (llm == null) {
            throw LlmUnavailableException("No LLM provider is configured. Set GROQ_API_KEY on the server.")
        }
        val userText = requirementsUserText(description, request)
        val (payload, origin) = try {
            callForJson(
                LlmRequest(requirementsSystemPrompt(webSearch = true), userText, jsonMode = false, webSearch = true),
                LlmRequirementsPayload.serializer(),
            ).requireRequirements() to RequirementOrigin.LLM_WEB_SEARCH
        } catch (e: ApiException) {
            log.warn("Web-search requirement lookup failed ({}), retrying without search", e.message)
            callForJson(
                LlmRequest(requirementsSystemPrompt(webSearch = false), userText, jsonMode = true),
                LlmRequirementsPayload.serializer(),
            ).requireRequirements() to RequirementOrigin.LLM_KNOWLEDGE
        }
        return ResolveRequirementsResponse(
            title = request.title.trim().ifBlank { payload.title.trim().ifBlank { description.take(60) } },
            countryCode = RequirementSanitizer.countryCode(request.countryCode),
            origin = origin,
            requirements = RequirementSanitizer.requirements(payload.requirements),
            assumptions = payload.assumptions.map { it.trim() }.filter { it.isNotEmpty() },
        )
    }

    private fun LlmRequirementsPayload.requireRequirements(): LlmRequirementsPayload {
        if (requirements.none { it.name.isNotBlank() }) throw LlmOutputException("LLM returned no requirements")
        return this
    }

    private suspend fun <T> callForJson(request: LlmRequest, serializer: KSerializer<T>): T {
        val provider = llm ?: throw LlmUnavailableException("No LLM provider is configured")
        val content = try {
            provider.complete(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw LlmUnavailableException("LLM call failed: ${e::class.simpleName}")
        }
        val json = JsonExtraction.extractJsonObject(content)
        return try {
            ApiJson.decodeFromJsonElement(serializer, json)
        } catch (e: SerializationException) {
            throw LlmOutputException("LLM response had an unexpected shape")
        } catch (e: IllegalArgumentException) {
            throw LlmOutputException("LLM response had an unexpected shape")
        }
    }

    private fun curatedResponse(scenario: CuratedScenario) = AnalyzeScenarioResponse(
        title = scenario.title,
        countryCode = scenario.countryCode,
        curatedScenarioId = scenario.id,
        questions = scenario.questions,
    )

    private fun validDescription(raw: String): String {
        val description = raw.trim()
        if (description.isEmpty()) throw InvalidInputException("Describe your scenario first")
        if (description.length > MAX_DESCRIPTION_LENGTH) {
            throw InvalidInputException("Scenario description is too long (max $MAX_DESCRIPTION_LENGTH characters)")
        }
        return description
    }

    private fun analyzeSystemPrompt(): String = buildString {
        appendLine("You help people work out which personal or official documents they need for a real-life scenario.")
        appendLine("Analyse the user's scenario and reply with ONLY a JSON object of this shape:")
        appendLine("""{"title": string, "countryCode": string|null, "curatedScenarioId": string|null, "questions": [{"id": string, "text": string, "options": [string], "allowFreeText": boolean}]}""")
        appendLine("- title: a short name for the scenario (max 8 words).")
        appendLine("- countryCode: ISO 3166-1 alpha-2 code of the country whose rules apply (the country of the authority or institution), or null if unclear.")
        appendLine("- curatedScenarioId: one id from the list below ONLY if the scenario clearly is that scenario AND the country is IN; otherwise null.")
        appendLine("- questions: at most 4 short follow-up questions whose answers change which documents are needed (for example country, nationality, employment type, first-time vs renewal). Give 2-5 options each when possible. Ask nothing already answered in the scenario. Use [] if no questions are needed.")
        appendLine("Curated scenarios:")
        curated.scenarios.forEach { appendLine("- ${it.id}: ${it.summary}") }
    }

    private fun requirementsSystemPrompt(webSearch: Boolean): String = buildString {
        appendLine("You list the personal or official documents a person must gather for a scenario.")
        if (webSearch) {
            appendLine("Search official government or institution websites to confirm the current requirements before answering.")
        }
        appendLine("Reply with ONLY a JSON object of this shape (no prose before or after):")
        appendLine("""{"title": string, "assumptions": [string], "requirements": [{"id": string, "name": string, "description": string, "acceptedTypes": [string], "mandatory": boolean, "specs": [string], "obtainGuide": {"steps": [string], "links": [{"title": string, "url": string}], "typicalFee": string|null, "typicalTime": string|null}, "citations": [{"title": string, "url": string}]}]}""")
        appendLine("Rules:")
        appendLine("- One requirement per document the person must provide; list alternatives that satisfy it in acceptedTypes.")
        appendLine("- acceptedTypes must use only these keys: ${DocumentType.entries.joinToString { it.key }}.")
        appendLine("- specs: format, number of copies, attestation, validity window, size limits when known.")
        appendLine("- obtainGuide: how to get the document if missing, with official portal links, and approximate fees and timelines (say \"approx.\").")
        appendLine("- citations: official sources (government or the institution itself) that state the requirement. Use [] rather than inventing URLs.")
        appendLine("- assumptions: anything you assumed about the person's situation.")
    }

    private fun requirementsUserText(description: String, request: ResolveRequirementsRequest): String = buildString {
        appendLine("Scenario: $description")
        if (request.title.isNotBlank()) appendLine("Title: ${request.title.trim()}")
        RequirementSanitizer.countryCode(request.countryCode)?.let { appendLine("Country: $it") }
        if (request.answers.isNotEmpty()) {
            appendLine("Follow-up answers:")
            request.answers.forEach { appendLine("- ${it.question.trim()} → ${it.answer.trim()}") }
        }
    }

    companion object {
        const val MAX_DESCRIPTION_LENGTH = 2000
    }
}
