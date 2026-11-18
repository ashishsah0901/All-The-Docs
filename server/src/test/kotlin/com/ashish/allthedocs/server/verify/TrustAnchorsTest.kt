package com.ashish.allthedocs.server.verify

import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrustAnchorsTest {
    @Test
    fun bundledRootsAreSelfSignedCcaIndiaCertificates() {
        val roots = TrustAnchors.bundled()
        assertEquals(4, roots.size)
        roots.forEach { root ->
            assertEquals(root.subjectX500Principal, root.issuerX500Principal)
            assertTrue(root.subjectX500Principal.name.contains("CCA India"))
            assertTrue(root.basicConstraints >= 0)
            root.verify(root.publicKey)
        }
    }

    @Test
    fun directoryLoadsPemAndDerAndSkipsOtherFiles() {
        val directory = Files.createTempDirectory("trust").toFile()
        try {
            val pemCert = TestPki.issue("CN=Pem Root", issuer = null, isCa = true).certificate
            val derCert = TestPki.issue("CN=Der Root", issuer = null, isCa = true).certificate
            val pem = "-----BEGIN CERTIFICATE-----\n" +
                Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pemCert.encoded) +
                "\n-----END CERTIFICATE-----\n"
            directory.resolve("a.pem").writeText(pem)
            directory.resolve("b.cer").writeBytes(derCert.encoded)
            directory.resolve("notes.txt").writeText("not a certificate")
            directory.resolve("broken.crt").writeText("garbage")

            val loaded = TrustAnchors.fromDirectory(directory)

            assertEquals(listOf(pemCert, derCert), loaded)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun environmentAddsExtraAnchorsToBundledOnes() {
        val directory = Files.createTempDirectory("trust").toFile()
        try {
            val extra = TestPki.issue("CN=Extra Root", issuer = null, isCa = true).certificate
            directory.resolve("extra.der").writeBytes(extra.encoded)

            val anchors = TrustAnchors.fromEnvironment { if (it == TrustAnchors.TRUST_DIR_ENV) directory.path else null }

            assertEquals(5, anchors.size)
            assertTrue(extra in anchors)
        } finally {
            directory.deleteRecursively()
        }
    }
}
