package com.ashish.allthedocs.server.verify

import com.ashish.allthedocs.api.SignatureStatus
import com.ashish.allthedocs.server.InvalidInputException
import com.ashish.allthedocs.server.TestFiles
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SignatureCheckerTest {
    private val trusting = SignatureChecker(listOf(TestPki.root.certificate))
    private val signed by lazy { TestPki.signedPdf() }

    @Test
    fun intactSignatureChainingToAnchorIsValid() {
        val result = trusting.check(signed)
        assertEquals(SignatureStatus.VALID, result.status, result.message)
        assertEquals("DS Test Issuing Department", result.signerName)
        val signedAt = Instant.parse(assertNotNull(result.signedAt))
        assertTrue(Duration.between(signedAt, Instant.now()).abs() < Duration.ofMinutes(5))
    }

    @Test
    fun intactSignatureWithoutAnchorsIsUntrusted() {
        val result = SignatureChecker(emptyList()).check(signed)
        assertEquals(SignatureStatus.UNTRUSTED, result.status)
        assertEquals("DS Test Issuing Department", result.signerName)
    }

    @Test
    fun signerFromOtherRootIsUntrusted() {
        val otherRoot = TestPki.issue("CN=Other Root, O=Elsewhere", issuer = null, isCa = true)
        val result = SignatureChecker(listOf(otherRoot.certificate)).check(signed)
        assertEquals(SignatureStatus.UNTRUSTED, result.status)
    }

    @Test
    fun alteredSignedBytesAreInvalid() {
        val tampered = signed.copyOf().also { it[11] = (it[11].toInt() xor 0x01).toByte() }
        val result = trusting.check(tampered)
        assertEquals(SignatureStatus.INVALID, result.status)
        assertTrue(result.message.contains("modified"))
    }

    @Test
    fun plainAppendAfterSigningIsInvalid() {
        val appended = signed + "\n% appended after signing\n".toByteArray()
        val result = trusting.check(appended)
        assertEquals(SignatureStatus.INVALID, result.status)
        assertEquals("The document was changed after it was signed", result.message)
    }

    @Test
    fun incrementalUpdateAfterSigningIsInvalid() {
        val updated = TestPki.incrementallyUpdated(signed)
        assertTrue(updated.size > signed.size)
        val result = trusting.check(updated)
        assertEquals(SignatureStatus.INVALID, result.status)
        assertEquals("The document was changed after it was signed", result.message)
    }

    @Test
    fun trailingNewlineAfterSigningIsTolerated() {
        assertEquals(SignatureStatus.VALID, trusting.check(signed + "\r\n".toByteArray()).status)
    }

    @Test
    fun unsignedPdfIsNotSigned() {
        val result = trusting.check(TestPki.unsignedPdf())
        assertEquals(SignatureStatus.NOT_SIGNED, result.status)
    }

    @Test
    fun imageIsNotSigned() {
        val result = trusting.check(TestFiles.png())
        assertEquals(SignatureStatus.NOT_SIGNED, result.status)
        assertEquals("Only PDFs carry digital signatures", result.message)
    }

    @Test
    fun corruptPdfIsBadInput() {
        assertFailsWith<InvalidInputException> { trusting.check("%PDF-1.7\nnot really a pdf".toByteArray()) }
    }
}
