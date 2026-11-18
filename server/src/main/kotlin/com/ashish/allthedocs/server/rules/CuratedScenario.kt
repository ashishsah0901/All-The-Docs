package com.ashish.allthedocs.server.rules

import com.ashish.allthedocs.api.DocumentRequirement
import com.ashish.allthedocs.api.FollowUpQuestion

data class CuratedScenario(
    val id: String,
    val title: String,
    val summary: String,
    val countryCode: String,
    val keywords: List<String>,
    val questions: List<FollowUpQuestion>,
    val requirements: List<CuratedRequirement>,
    val assumptions: List<String> = emptyList(),
) {
    fun requirementsFor(answers: Map<String, String>): List<DocumentRequirement> =
        requirements.filter { it.condition.matches(answers) }.map { it.requirement }
}

data class CuratedRequirement(
    val requirement: DocumentRequirement,
    val condition: Condition = Condition.Always,
)

sealed interface Condition {
    fun matches(answers: Map<String, String>): Boolean

    data object Always : Condition {
        override fun matches(answers: Map<String, String>) = true
    }

    data class AnswerIn(
        val questionId: String,
        val values: Set<String>,
        val includeIfUnanswered: Boolean = false,
    ) : Condition {
        override fun matches(answers: Map<String, String>): Boolean {
            val answer = answers[questionId]?.trim()?.takeIf { it.isNotEmpty() } ?: return includeIfUnanswered
            return values.any { it.equals(answer, ignoreCase = true) }
        }
    }

    data class AllOf(val conditions: List<Condition>) : Condition {
        override fun matches(answers: Map<String, String>) = conditions.all { it.matches(answers) }
    }

    data class AnyOf(val conditions: List<Condition>) : Condition {
        override fun matches(answers: Map<String, String>) = conditions.any { it.matches(answers) }
    }
}

fun answer(questionId: String, vararg values: String, includeIfUnanswered: Boolean = false): Condition =
    Condition.AnswerIn(questionId, values.toSet(), includeIfUnanswered)

infix fun Condition.and(other: Condition): Condition = Condition.AllOf(listOf(this, other))

infix fun Condition.or(other: Condition): Condition = Condition.AnyOf(listOf(this, other))
