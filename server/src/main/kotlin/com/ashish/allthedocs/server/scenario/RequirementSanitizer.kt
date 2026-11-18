package com.ashish.allthedocs.server.scenario

import com.ashish.allthedocs.api.DocumentRequirement
import com.ashish.allthedocs.api.FollowUpQuestion
import com.ashish.allthedocs.api.ObtainGuide
import com.ashish.allthedocs.api.SourceLink
import com.ashish.allthedocs.model.DocumentType
import kotlinx.serialization.Serializable

@Serializable
internal data class LlmLink(val title: String = "", val url: String = "")

@Serializable
internal data class LlmObtainGuide(
    val steps: List<String> = emptyList(),
    val links: List<LlmLink> = emptyList(),
    val typicalFee: String? = null,
    val typicalTime: String? = null,
)

@Serializable
internal data class LlmRequirement(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val acceptedTypes: List<DocumentType> = emptyList(),
    val mandatory: Boolean = true,
    val specs: List<String> = emptyList(),
    val obtainGuide: LlmObtainGuide? = null,
    val citations: List<LlmLink> = emptyList(),
)

@Serializable
internal data class LlmRequirementsPayload(
    val title: String = "",
    val requirements: List<LlmRequirement> = emptyList(),
    val assumptions: List<String> = emptyList(),
)

@Serializable
internal data class LlmQuestion(
    val id: String = "",
    val text: String = "",
    val options: List<String> = emptyList(),
    val allowFreeText: Boolean? = null,
)

@Serializable
internal data class LlmAnalysisPayload(
    val title: String = "",
    val countryCode: String? = null,
    val curatedScenarioId: String? = null,
    val questions: List<LlmQuestion> = emptyList(),
)

internal object RequirementSanitizer {
    const val MAX_REQUIREMENTS = 30
    const val MAX_QUESTIONS = 4
    const val MAX_OPTIONS = 6

    fun requirements(items: List<LlmRequirement>): List<DocumentRequirement> {
        val usedIds = mutableSetOf<String>()
        return items
            .filter { it.name.isNotBlank() }
            .take(MAX_REQUIREMENTS)
            .map { item ->
                val id = uniqueId(slug(item.id.ifBlank { item.name }), usedIds)
                DocumentRequirement(
                    id = id,
                    name = item.name.trim(),
                    description = item.description.trim(),
                    acceptedTypes = item.acceptedTypes.distinct().ifEmpty { listOf(DocumentType.OTHER) },
                    mandatory = item.mandatory,
                    specs = cleanStrings(item.specs),
                    obtainGuide = item.obtainGuide?.let(::guide),
                    citations = links(item.citations),
                )
            }
    }

    fun questions(items: List<LlmQuestion>): List<FollowUpQuestion> {
        val usedIds = mutableSetOf<String>()
        return items
            .filter { it.text.isNotBlank() }
            .take(MAX_QUESTIONS)
            .mapIndexed { index, item ->
                val options = cleanStrings(item.options).distinct().take(MAX_OPTIONS)
                FollowUpQuestion(
                    id = uniqueId(slug(item.id).ifBlank { "q${index + 1}" }, usedIds),
                    text = item.text.trim(),
                    options = options,
                    allowFreeText = item.allowFreeText ?: options.isEmpty(),
                )
            }
    }

    fun links(items: List<LlmLink>): List<SourceLink> =
        items.mapNotNull { link ->
            val url = link.url.trim()
            if (!isHttpUrl(url)) return@mapNotNull null
            SourceLink(title = link.title.trim().ifBlank { hostOf(url) }, url = url)
        }.distinctBy { it.url }

    fun countryCode(raw: String?): String? =
        raw?.trim()?.uppercase()?.takeIf { it.length == 2 && it.all(Char::isLetter) }

    private fun guide(item: LlmObtainGuide): ObtainGuide? {
        val result = ObtainGuide(
            steps = cleanStrings(item.steps),
            links = links(item.links),
            typicalFee = item.typicalFee?.trim()?.ifBlank { null },
            typicalTime = item.typicalTime?.trim()?.ifBlank { null },
        )
        return result.takeIf { it.steps.isNotEmpty() || it.links.isNotEmpty() || it.typicalFee != null || it.typicalTime != null }
    }

    private fun cleanStrings(items: List<String>): List<String> = items.map { it.trim() }.filter { it.isNotEmpty() }

    private fun isHttpUrl(url: String): Boolean {
        val lower = url.lowercase()
        return (lower.startsWith("https://") || lower.startsWith("http://")) && hostOf(url).isNotBlank() && url.none { it.isWhitespace() }
    }

    private fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/').substringBefore('?')

    private fun slug(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(48)

    private fun uniqueId(base: String, used: MutableSet<String>): String {
        val root = base.ifBlank { "item" }
        var candidate = root
        var suffix = 2
        while (!used.add(candidate)) {
            candidate = "${root}_$suffix"
            suffix++
        }
        return candidate
    }
}
