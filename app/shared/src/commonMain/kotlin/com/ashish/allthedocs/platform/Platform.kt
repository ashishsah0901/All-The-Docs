package com.ashish.allthedocs.platform

import androidx.compose.runtime.Composable
import com.ashish.allthedocs.model.DocumentSource
import kotlinx.datetime.LocalDate

interface SecureFileStore {
    suspend fun write(name: String, bytes: ByteArray)
    suspend fun read(name: String): ByteArray
    suspend fun delete(name: String)
}

interface ExpiryReminderScheduler {
    suspend fun schedule(documentId: String, title: String, expiryDate: LocalDate)
    suspend fun cancel(documentId: String)
}

interface DocumentViewer {
    suspend fun open(bytes: ByteArray, mimeType: String, fileName: String)
}

class CapturedFile(
    val bytes: ByteArray,
    val mimeType: String,
    val fileName: String,
    val source: DocumentSource = DocumentSource.FILE,
)

interface DocumentCaptureLauncher {
    fun scan()
    fun pickFile()
    fun importFromDigiLocker()
}

@Composable
expect fun rememberDocumentCaptureLauncher(
    onCaptured: (CapturedFile) -> Unit,
    onError: (String) -> Unit,
): DocumentCaptureLauncher

expect fun defaultServerUrl(): String

expect fun sha256(bytes: ByteArray): ByteArray

expect fun secureRandomBytes(size: Int): ByteArray

val REMINDER_LEAD_DAYS = listOf(30, 7)

val SUPPORTED_MIME_TYPES = listOf("application/pdf", "image/jpeg", "image/png")

fun sniffMimeType(bytes: ByteArray): String? = when {
    bytes.size >= 4 && bytes[0] == '%'.code.toByte() && bytes[1] == 'P'.code.toByte() &&
        bytes[2] == 'D'.code.toByte() && bytes[3] == 'F'.code.toByte() -> "application/pdf"
    bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "image/jpeg"
    bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
        bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() -> "image/png"
    else -> null
}
