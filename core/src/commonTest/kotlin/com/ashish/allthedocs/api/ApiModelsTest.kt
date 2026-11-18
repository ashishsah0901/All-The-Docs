package com.ashish.allthedocs.api

import com.ashish.allthedocs.model.DocumentType
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class ApiModelsTest {
    @Test
    fun unknownDocumentTypeKeyDecodesAsOther() {
        val json = """{"id":"x","name":"X","acceptedTypes":["passport","made_up_type"]}"""
        val requirement = ApiJson.decodeFromString<DocumentRequirement>(json)
        assertEquals(listOf(DocumentType.PASSPORT, DocumentType.OTHER), requirement.acceptedTypes)
    }

    @Test
    fun verificationResultRoundTrips() {
        val result = VerificationResult(
            detectedType = DocumentType.PAN_CARD,
            matchesExpected = true,
            expiryDate = LocalDate(2030, 5, 1),
            confidence = 0.9,
        )
        val encoded = ApiJson.encodeToString(VerificationResult.serializer(), result)
        assertEquals(result, ApiJson.decodeFromString(VerificationResult.serializer(), encoded))
        assertEquals(true, encoded.contains("\"detectedType\":\"pan_card\""))
    }
}
