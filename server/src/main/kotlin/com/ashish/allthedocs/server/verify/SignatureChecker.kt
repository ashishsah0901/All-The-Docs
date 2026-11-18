package com.ashish.allthedocs.server.verify

import com.ashish.allthedocs.api.SignatureCheckResult
import com.ashish.allthedocs.api.SignatureStatus
import com.ashish.allthedocs.server.InvalidInputException
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import org.bouncycastle.asn1.ASN1InputStream
import org.bouncycastle.asn1.cms.Attribute
import org.bouncycastle.asn1.cms.CMSAttributes
import org.bouncycastle.asn1.cms.ContentInfo
import org.bouncycastle.asn1.cms.Time
import org.bouncycastle.asn1.x500.RDN
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x500.style.IETFUtils
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cms.CMSException
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.CMSSignerDigestMismatchException
import org.bouncycastle.cms.SignerInformation
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.OperatorCreationException
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.cert.CertPathBuilder
import java.security.cert.CertPathBuilderException
import java.security.cert.CertStore
import java.security.cert.CollectionCertStoreParameters
import java.security.cert.PKIXBuilderParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509CertSelector
import java.security.cert.X509Certificate
import java.util.Date

class SignatureChecker(trustAnchors: Collection<X509Certificate>) {
    private val anchors: Set<TrustAnchor> = trustAnchors.map { TrustAnchor(it, null) }.toSet()
    private val provider = BouncyCastleProvider()
    private val converter = JcaX509CertificateConverter().setProvider(provider)

    private class SignatureData(
        val byteRange: IntArray,
        val contents: ByteArray,
        val signedContent: ByteArray,
        val subFilter: String?,
        val signDate: Date?,
    ) {
        val coveredEnd: Long get() = byteRange[2].toLong() + byteRange[3].toLong()
    }

    private class Outcome(
        val status: SignatureStatus,
        val signerName: String?,
        val signedAt: Date?,
        val message: String,
    )

    fun check(bytes: ByteArray): SignatureCheckResult {
        if (DocumentRenderer.detectType(bytes) != SupportedFileType.PDF) {
            return SignatureCheckResult(SignatureStatus.NOT_SIGNED, message = "Only PDFs carry digital signatures")
        }
        val signatures = readSignatures(bytes)
        if (signatures.isEmpty()) {
            return SignatureCheckResult(SignatureStatus.NOT_SIGNED, message = "The PDF has no digital signature")
        }
        val outermost = signatures.maxBy { it.coveredEnd }
        val outcomes = signatures.associateWith { verify(it) }
        val outermostOutcome = outcomes.getValue(outermost)
        val failure = outcomes.values.firstOrNull { it.status == SignatureStatus.INVALID }
        val result = when {
            failure != null -> failure
            !coversWholeFile(outermost, bytes) -> Outcome(
                SignatureStatus.INVALID,
                outermostOutcome.signerName,
                outermostOutcome.signedAt,
                "The document was changed after it was signed",
            )
            else -> outermostOutcome
        }
        return SignatureCheckResult(
            status = result.status,
            signerName = result.signerName,
            signedAt = result.signedAt?.toInstant()?.toString(),
            message = result.message,
        )
    }

    private fun readSignatures(bytes: ByteArray): List<SignatureData> = try {
        Loader.loadPDF(bytes).use { document ->
            document.signatureDictionaries.mapNotNull { signature -> signature.toData(bytes) }
        }
    } catch (e: InvalidPasswordException) {
        throw InvalidInputException("Password-protected PDFs can't be checked")
    } catch (e: IOException) {
        throw InvalidInputException("The PDF couldn't be read")
    }

    private fun PDSignature.toData(bytes: ByteArray): SignatureData? {
        val range = byteRange ?: return null
        if (range.size != 4) return null
        return SignatureData(
            byteRange = range,
            contents = getContents(bytes),
            signedContent = getSignedContent(bytes),
            subFilter = subFilter,
            signDate = signDate?.time,
        )
    }

    private fun coversWholeFile(signature: SignatureData, bytes: ByteArray): Boolean {
        val end = signature.coveredEnd
        if (end > bytes.size) return false
        for (index in end.toInt() until bytes.size) {
            if (bytes[index].toInt() !in TRAILING_WHITESPACE) return false
        }
        return true
    }

    private fun verify(signature: SignatureData): Outcome = try {
        val signedData = when (signature.subFilter) {
            SUBFILTER_PKCS7_DETACHED, SUBFILTER_CADES_DETACHED ->
                CMSSignedData(CMSProcessableByteArray(signature.signedContent), contentInfo(signature.contents))
            SUBFILTER_PKCS7_SHA1 -> CMSSignedData(contentInfo(signature.contents))
            else -> return Outcome(
                SignatureStatus.INVALID,
                null,
                signature.signDate,
                "Unsupported signature format (${signature.subFilter ?: "unknown"})",
            )
        }
        verifySignedData(signedData, signature)
    } catch (e: CMSException) {
        unreadable(signature)
    } catch (e: IOException) {
        unreadable(signature)
    } catch (e: OperatorCreationException) {
        unreadable(signature)
    } catch (e: GeneralSecurityException) {
        unreadable(signature)
    } catch (e: IllegalArgumentException) {
        unreadable(signature)
    } catch (e: ClassCastException) {
        unreadable(signature)
    }

    private fun contentInfo(contents: ByteArray): ContentInfo =
        ASN1InputStream(contents).use { ContentInfo.getInstance(it.readObject()) }

    private fun verifySignedData(signedData: CMSSignedData, signature: SignatureData): Outcome {
        val signer: SignerInformation = signedData.signerInfos.signers.firstOrNull() ?: return unreadable(signature)
        @Suppress("UNCHECKED_CAST")
        val holders = signedData.certificates.getMatches(null) as Collection<X509CertificateHolder>
        val signerHolder = holders.firstOrNull { signer.sid.match(it) }
            ?: return Outcome(SignatureStatus.INVALID, null, signature.signDate, "The signer's certificate is missing from the signature")
        val signerName = nameOf(signerHolder.subject)
        val signedAt = signingTime(signer) ?: signature.signDate

        if (signature.subFilter == SUBFILTER_PKCS7_SHA1) {
            val encapsulated = signedData.signedContent?.content as? ByteArray
            val digest = MessageDigest.getInstance("SHA-1").digest(signature.signedContent)
            if (encapsulated == null || !MessageDigest.isEqual(encapsulated, digest)) return tampered(signerName, signedAt)
        }
        val intact = try {
            signer.verify(JcaSimpleSignerInfoVerifierBuilder().setProvider(provider).build(signerHolder))
        } catch (e: CMSSignerDigestMismatchException) {
            false
        }
        if (!intact) return tampered(signerName, signedAt)

        val signerCertificate = converter.getCertificate(signerHolder)
        val chain = holders.map { converter.getCertificate(it) }
        return if (chainsToAnchor(signerCertificate, chain, signedAt)) {
            Outcome(
                SignatureStatus.VALID,
                signerName,
                signedAt,
                "Signed by ${signerName ?: "the issuer"}. The signature is intact and chains to a trusted Indian certifying authority.",
            )
        } else {
            Outcome(
                SignatureStatus.UNTRUSTED,
                signerName,
                signedAt,
                "Signed by ${signerName ?: "an unknown signer"}. The signature is intact, but the signer isn't from a trusted certifying authority.",
            )
        }
    }

    private fun chainsToAnchor(signer: X509Certificate, certificates: List<X509Certificate>, signedAt: Date?): Boolean {
        if (anchors.isEmpty()) return false
        if (anchors.any { it.trustedCert == signer }) return true
        return try {
            val selector = X509CertSelector().apply { certificate = signer }
            val parameters = PKIXBuilderParameters(anchors, selector).apply {
                isRevocationEnabled = false
                date = signedAt ?: Date()
                addCertStore(CertStore.getInstance("Collection", CollectionCertStoreParameters(certificates)))
            }
            CertPathBuilder.getInstance("PKIX").build(parameters)
            true
        } catch (e: CertPathBuilderException) {
            false
        } catch (e: GeneralSecurityException) {
            false
        }
    }

    private fun signingTime(signer: SignerInformation): Date? {
        val attribute: Attribute = signer.signedAttributes?.get(CMSAttributes.signingTime) ?: return null
        val value = attribute.attrValues.takeIf { it.size() > 0 }?.getObjectAt(0) ?: return null
        return runCatching { Time.getInstance(value).date }.getOrNull()
    }

    private fun nameOf(name: X500Name): String? {
        val rdns: Array<RDN> = name.getRDNs(BCStyle.CN).takeIf { it.isNotEmpty() } ?: name.getRDNs(BCStyle.O)
        return rdns.firstOrNull()?.first?.value?.let { IETFUtils.valueToString(it) }?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun tampered(signerName: String?, signedAt: Date?) = Outcome(
        SignatureStatus.INVALID,
        signerName,
        signedAt,
        "The digital signature doesn't match the document. It may have been modified.",
    )

    private fun unreadable(signature: SignatureData) = Outcome(
        SignatureStatus.INVALID,
        null,
        signature.signDate,
        "The digital signature couldn't be read",
    )

    private companion object {
        const val SUBFILTER_PKCS7_DETACHED = "adbe.pkcs7.detached"
        const val SUBFILTER_CADES_DETACHED = "ETSI.CAdES.detached"
        const val SUBFILTER_PKCS7_SHA1 = "adbe.pkcs7.sha1"
        val TRAILING_WHITESPACE = setOf(0x00, 0x09, 0x0A, 0x0D, 0x20)
    }
}
