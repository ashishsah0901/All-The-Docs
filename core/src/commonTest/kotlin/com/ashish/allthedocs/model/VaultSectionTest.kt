package com.ashish.allthedocs.model

import kotlin.test.Test
import kotlin.test.assertEquals

class VaultSectionTest {
    @Test
    fun verifiedIssuableDocumentWithoutIssuesIsIssued() =
        assertEquals(VaultSection.ISSUED, VaultSection.of(DocumentType.PAN_CARD, verified = true, hasIssues = false))

    @Test
    fun unverifiedDocumentIsUploaded() =
        assertEquals(VaultSection.UPLOADED, VaultSection.of(DocumentType.PAN_CARD, verified = false, hasIssues = false))

    @Test
    fun verifiedDocumentWithIssuesIsUploaded() =
        assertEquals(VaultSection.UPLOADED, VaultSection.of(DocumentType.PASSPORT, verified = true, hasIssues = true))

    @Test
    fun selfMadeDocumentIsAlwaysUploaded() =
        assertEquals(VaultSection.UPLOADED, VaultSection.of(DocumentType.CANCELLED_CHEQUE, verified = true, hasIssues = false))

    @Test
    fun validSignatureMakesIssuedWithoutAiVerification() =
        assertEquals(VaultSection.ISSUED, VaultSection.of(DocumentType.PAN_CARD, verified = false, hasIssues = false, signatureValid = true))

    @Test
    fun validSignatureWithIssuesStaysUploaded() =
        assertEquals(VaultSection.UPLOADED, VaultSection.of(DocumentType.PAN_CARD, verified = false, hasIssues = true, signatureValid = true))
}

class DocumentTypeGuesserTest {
    @Test
    fun digiLockerDocumentCodesAreRecognised() {
        assertEquals(DocumentType.PAN_CARD, DocumentTypeGuesser.fromFileName("PANCR-XXXXX0000X.pdf"))
        assertEquals(DocumentType.DRIVING_LICENCE, DocumentTypeGuesser.fromFileName("DRVLC-MH0000000000000.pdf"))
        assertEquals(DocumentType.AADHAAR, DocumentTypeGuesser.fromFileName("ADHAR-xxxx.pdf"))
    }

    @Test
    fun keywordsAreRecognised() {
        assertEquals(DocumentType.EDUCATION_CERTIFICATE, DocumentTypeGuesser.fromFileName("Class X Marksheet.pdf"))
        assertEquals(DocumentType.FORM_16, DocumentTypeGuesser.fromFileName("Form16_FY2025.pdf"))
    }

    @Test
    fun unknownNameReturnsNull() = assertEquals(null, DocumentTypeGuesser.fromFileName("scan_0001.jpg"))
}
