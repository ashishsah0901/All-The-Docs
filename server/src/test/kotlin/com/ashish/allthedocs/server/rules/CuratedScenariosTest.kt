package com.ashish.allthedocs.server.rules

import com.ashish.allthedocs.model.DocumentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CuratedScenariosTest {
    private val repository = CuratedScenarioRepository.default

    private fun ids(scenario: CuratedScenario, vararg answers: Pair<String, String>) =
        scenario.requirementsFor(answers.toMap()).map { it.id }

    @Test
    fun everyScenarioIsWellFormed() {
        repository.scenarios.forEach { scenario ->
            assertEquals("IN", scenario.countryCode)
            assertTrue(scenario.questions.size <= 4, scenario.id)
            val questionIds = scenario.questions.map { it.id }.toSet()
            scenario.requirements.forEach { curated ->
                val requirement = curated.requirement
                assertTrue(requirement.acceptedTypes.isNotEmpty(), requirement.id)
                assertFalse(DocumentType.OTHER in requirement.acceptedTypes, requirement.id)
                (requirement.citations + requirement.obtainGuide?.links.orEmpty()).forEach {
                    assertTrue(it.url.startsWith("https://"), it.url)
                }
                referencedQuestions(curated.condition).forEach { assertTrue(it in questionIds, "${scenario.id}: $it") }
            }
            scenario.questions.forEach { question ->
                scenario.requirements.map { it.condition }.flatMap(::answerConditions)
                    .filter { it.questionId == question.id }
                    .flatMap { it.values }
                    .forEach { value -> assertTrue(value in question.options, "${scenario.id}: '$value' not an option") }
            }
        }
    }

    @Test
    fun passportFreshMinorNeedsParentalConsentButNotOldPassport() {
        val ids = ids(IndiaCuratedScenarios.passport, "application_type" to "Fresh", "applicant" to "Minor (below 18)")
        assertTrue("parental_consent" in ids)
        assertTrue("dob_proof" in ids)
        assertFalse("old_passport" in ids)
        assertFalse("non_ecr_proof" in ids)
    }

    @Test
    fun unansweredQuestionsKeepDefaultRequirements() {
        val ids = ids(IndiaCuratedScenarios.homeLoan)
        assertTrue("salary_slips" in ids)
        assertFalse("itr" in ids)
        assertTrue("title_documents" in ids)
    }

    @Test
    fun selfEmployedHomeLoanSwapsIncomeProofs() {
        val ids = ids(IndiaCuratedScenarios.homeLoan, "employment_type" to "Self-employed", "property_stage" to "Buying from a builder")
        assertTrue("itr" in ids)
        assertTrue("financials" in ids)
        assertFalse("salary_slips" in ids)
        assertFalse("title_documents" in ids)
        assertTrue("approved_plan" in ids)
    }

    @Test
    fun answerMatchingIsCaseInsensitive() {
        val ids = ids(IndiaCuratedScenarios.savingsAccount, "has_pan" to "no")
        assertTrue("form_60" in ids)
        assertFalse("pan" in ids)
    }

    @Test
    fun itrHraNeedsOldRegime() {
        val newRegime = ids(IndiaCuratedScenarios.itrSalaried, "tax_regime" to "New regime", "hra" to "Yes")
        assertFalse("rent_proofs" in newRegime)
        assertFalse("investment_proofs" in newRegime)
        val oldRegime = ids(
            IndiaCuratedScenarios.itrSalaried,
            "tax_regime" to "Old regime",
            "hra" to "Yes",
            "capital_gains" to "Yes",
            "house_property" to "Yes, with a home loan",
        )
        assertTrue("rent_proofs" in oldRegime)
        assertTrue("capital_gains" in oldRegime)
        assertTrue("home_loan_interest" in oldRegime)
    }

    @Test
    fun jobSwitchMidYearNeedsPreviousIncomeDetails() {
        val ids = ids(IndiaCuratedScenarios.jobOnboarding, "experience" to "Experienced", "mid_year_switch" to "Yes")
        assertTrue("previous_income_details" in ids)
        assertTrue("relieving_letter" in ids)
        val fresher = ids(IndiaCuratedScenarios.jobOnboarding, "experience" to "Fresher", "mid_year_switch" to "Yes")
        assertFalse("previous_income_details" in fresher)
    }

    @Test
    fun keywordMatching() {
        assertEquals("in.home_loan", repository.matchByKeywords("Need a housing loan for my flat")?.id)
        assertEquals("in.itr_salaried", repository.matchByKeywords("File ITR this July")?.id)
        assertEquals("in.job_onboarding", repository.matchByKeywords("Joining formalities at my new job")?.id)
        assertNull(repository.matchByKeywords("Adopting a cat"))
        assertNull(repository.matchByKeywords("Unpassported traveller"))
    }

    private fun referencedQuestions(condition: Condition): List<String> = answerConditions(condition).map { it.questionId }

    private fun answerConditions(condition: Condition): List<Condition.AnswerIn> = when (condition) {
        is Condition.AnswerIn -> listOf(condition)
        is Condition.AllOf -> condition.conditions.flatMap(::answerConditions)
        is Condition.AnyOf -> condition.conditions.flatMap(::answerConditions)
        Condition.Always -> emptyList()
    }
}
