package com.ashish.allthedocs.server.verify

import java.io.ByteArrayInputStream
import java.io.File
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

object TrustAnchors {
    const val TRUST_DIR_ENV = "SIGNATURE_TRUST_DIR"

    private val bundledResources = listOf(
        "trust/cca-india-2022.pem",
        "trust/cca-india-2022-spl.pem",
        "trust/cca-india-2014.pem",
        "trust/cca-india-2015-spl.pem",
    )

    private val certificateExtensions = setOf("cer", "crt", "pem", "der")

    fun bundled(): List<X509Certificate> = bundledResources.flatMap { path ->
        val bytes = requireNotNull(TrustAnchors::class.java.classLoader.getResourceAsStream(path)) {
            "Missing bundled trust anchor $path"
        }.use { it.readBytes() }
        parse(bytes)
    }

    fun fromDirectory(directory: File): List<X509Certificate> =
        directory.listFiles().orEmpty()
            .filter { it.isFile && it.extension.lowercase() in certificateExtensions }
            .sortedBy { it.name }
            .flatMap { file -> runCatching { parse(file.readBytes()) }.getOrDefault(emptyList()) }

    fun fromEnvironment(env: (String) -> String? = System::getenv): List<X509Certificate> {
        val extra = env(TRUST_DIR_ENV)?.takeIf { it.isNotBlank() }?.let { fromDirectory(File(it)) }.orEmpty()
        return (bundled() + extra).distinctBy { it.encoded.contentHashCode() }
    }

    internal fun parse(bytes: ByteArray): List<X509Certificate> {
        val factory = CertificateFactory.getInstance("X.509")
        return try {
            factory.generateCertificates(ByteArrayInputStream(bytes)).filterIsInstance<X509Certificate>()
        } catch (e: CertificateException) {
            emptyList()
        }
    }
}
