package com.ashish.allthedocs.server.scenario

import com.ashish.allthedocs.model.DocumentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RequirementSanitizerTest {
    @Test
    fun dropsNamelessItemsAndDeduplicatesIds() {
        val result = RequirementSanitizer.requirements(
            listOf(
                LlmRequirement(id = "", name = "Proof of Address!"),
                LlmRequirement(id = "proof_of_address", name = "Another"),
                LlmRequirement(id = "x", name = "  "),
            ),
        )
        assertEquals(listOf("proof_of_address", "proof_of_address_2"), result.map { it.id })
        assertEquals(listOf(DocumentType.OTHER), result[0].acceptedTypes)
    }

    @Test
    fun keepsOnlyHttpLinksAndFillsTitles() {
        val links = RequirementSanitizer.links(
            listOf(
                LlmLink("", "https://www.passportindia.gov.in/page"),
                LlmLink("ftp", "ftp://example.com"),
                LlmLink("js", "javascript:alert(1)"),
                LlmLink("spaces", "https://exa mple.com"),
                LlmLink("dup", "https://www.passportindia.gov.in/page"),
            ),
        )
        assertEquals(1, links.size)
        assertEquals("www.passportindia.gov.in", links.single().title)
    }

    @Test
    fun emptyObtainGuideBecomesNull() {
        val result = RequirementSanitizer.requirements(listOf(LlmRequirement(name = "A", obtainGuide = LlmObtainGuide(steps = listOf(" ")))))
        assertNull(result.single().obtainGuide)
    }

    @Test
    fun capsQuestionsAndOptions() {
        val questions = RequirementSanitizer.questions(
            List(6) { LlmQuestion(text = "Q$it", options = List(8) { o -> "o$o" } + "o1") },
        )
        assertEquals(RequirementSanitizer.MAX_QUESTIONS, questions.size)
        assertTrue(questions.all { it.options.size == RequirementSanitizer.MAX_OPTIONS && !it.allowFreeText })
        assertEquals(listOf("q1", "q2", "q3", "q4"), questions.map { it.id })
    }

    @Test
    fun countryCodeValidation() {
        assertEquals("IN", RequirementSanitizer.countryCode(" in "))
        assertNull(RequirementSanitizer.countryCode("IND"))
        assertNull(RequirementSanitizer.countryCode("1N"))
    }
}
