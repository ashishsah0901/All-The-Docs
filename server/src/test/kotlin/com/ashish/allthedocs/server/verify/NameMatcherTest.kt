package com.ashish.allthedocs.server.verify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NameMatcherTest {
    @Test
    fun orderAndCaseInsensitive() = assertEquals(true, NameMatcher.matches("Ashish Sah", "SAH ASHISH"))

    @Test
    fun ignoresHonorificsPunctuationAndAccents() =
        assertEquals(true, NameMatcher.matches("Dr. José  Álvarez", "jose alvarez"))

    @Test
    fun allowsMiddleNameOnDocument() = assertEquals(true, NameMatcher.matches("Ashish Sah", "Ashish Kumar Sah"))

    @Test
    fun allowsInitials() = assertEquals(true, NameMatcher.matches("A. K. Sah", "Ashish Kumar Sah"))

    @Test
    fun rejectsDifferentNames() = assertEquals(false, NameMatcher.matches("Ashish Sah", "Priya Sharma"))

    @Test
    fun singleTokenFoundInFullNameIsInconclusive() = assertNull(NameMatcher.matches("Ashish", "Ashish Sah"))

    @Test
    fun rejectsSingleTokenMissingFromFullName() = assertEquals(false, NameMatcher.matches("Priya", "Ashish Sah"))

    @Test
    fun nullWhenEitherSideMissing() {
        assertNull(NameMatcher.matches(null, "Ashish Sah"))
        assertNull(NameMatcher.matches("Ashish Sah", "  "))
    }
}
