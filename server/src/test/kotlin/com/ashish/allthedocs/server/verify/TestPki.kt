package com.ashish.allthedocs.server.verify

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Date
import java.util.concurrent.TimeUnit

class TestIdentity(val keyPair: KeyPair, val certificate: X509Certificate)

object TestPki {
    private const val SIGNATURE_ALGORITHM = "SHA256withRSA"
    private var serial = 1L

    val root: TestIdentity by lazy { issue("CN=AllTheDocs Test Root CA, O=AllTheDocs Test", issuer = null, isCa = true) }
    val leaf: TestIdentity by lazy { issue("CN=DS Test Issuing Department, O=AllTheDocs Test", issuer = root, isCa = false) }

    fun issue(subject: String, issuer: TestIdentity?, isCa: Boolean): TestIdentity {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val now = System.currentTimeMillis()
        val issuerName = issuer?.let { JcaX509CertificateHolder(it.certificate).subject } ?: X500Name(subject)
        val builder = JcaX509v3CertificateBuilder(
            issuerName,
            BigInteger.valueOf(serial++),
            Date(now - TimeUnit.DAYS.toMillis(1)),
            Date(now + TimeUnit.DAYS.toMillis(365)),
            X500Name(subject),
            keyPair.public,
        )
            .addExtension(Extension.basicConstraints, true, BasicConstraints(isCa))
            .addExtension(
                Extension.keyUsage,
                true,
                KeyUsage(if (isCa) KeyUsage.keyCertSign or KeyUsage.cRLSign else KeyUsage.digitalSignature or KeyUsage.nonRepudiation),
            )
        val signerKey = issuer?.keyPair?.private ?: keyPair.private
        val holder = builder.build(JcaContentSignerBuilder(SIGNATURE_ALGORITHM).build(signerKey))
        return TestIdentity(keyPair, JcaX509CertificateConverter().getCertificate(holder))
    }

    fun unsignedPdf(text: String = "Test certificate"): ByteArray = PDDocument().use { document ->
        val page = PDPage()
        document.addPage(page)
        PDPageContentStream(document, page).use { stream ->
            stream.beginText()
            stream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 14f)
            stream.newLineAtOffset(72f, 700f)
            stream.showText(text)
            stream.endText()
        }
        ByteArrayOutputStream().also { document.save(it) }.toByteArray()
    }

    fun signedPdf(
        signer: TestIdentity = leaf,
        chain: List<X509Certificate> = listOf(leaf.certificate, root.certificate),
    ): ByteArray = Loader.loadPDF(unsignedPdf()).use { document ->
        val signature = PDSignature().apply {
            setFilter(PDSignature.FILTER_ADOBE_PPKLITE)
            setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED)
            name = "DS Test Issuing Department"
            signDate = Calendar.getInstance()
        }
        document.addSignature(signature, cmsSigner(signer, chain))
        ByteArrayOutputStream().also { document.saveIncremental(it) }.toByteArray()
    }

    fun incrementallyUpdated(signed: ByteArray): ByteArray = Loader.loadPDF(signed).use { document ->
        document.documentInformation.title = "Changed after signing"
        document.documentInformation.cosObject.isNeedToBeUpdated = true
        ByteArrayOutputStream().also { document.saveIncremental(it) }.toByteArray()
    }

    private fun cmsSigner(signer: TestIdentity, chain: List<X509Certificate>) = SignatureInterface { content ->
        val generator = CMSSignedDataGenerator().apply {
            addSignerInfoGenerator(
                JcaSignerInfoGeneratorBuilder(JcaDigestCalculatorProviderBuilder().build())
                    .build(JcaContentSignerBuilder(SIGNATURE_ALGORITHM).build(signer.keyPair.private), signer.certificate),
            )
            addCertificates(JcaCertStore(chain))
        }
        generator.generate(CMSProcessableByteArray(content.readAllBytes()), false).encoded
    }
}
