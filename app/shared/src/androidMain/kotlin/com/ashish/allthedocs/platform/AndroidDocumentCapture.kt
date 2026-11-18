package com.ashish.allthedocs.platform

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.ashish.allthedocs.api.ApiRoutes
import com.ashish.allthedocs.model.DocumentSource
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
actual fun rememberDocumentCaptureLauncher(
    onCaptured: (CapturedFile) -> Unit,
    onError: (String) -> Unit,
): DocumentCaptureLauncher {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val currentOnCaptured by rememberUpdatedState(onCaptured)
    val currentOnError by rememberUpdatedState(onError)

    fun deliver(uri: Uri, fallbackName: String, fallbackMime: String, source: DocumentSource) {
        scope.launch {
            try {
                currentOnCaptured(readCapturedFile(context, uri, fallbackName, fallbackMime, source))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentOnError(e.message ?: "Couldn't read the file")
            }
        }
    }

    val scanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val pdfUri = GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pdf?.uri
        if (pdfUri == null) {
            currentOnError("The scan didn't produce a document")
        } else {
            deliver(pdfUri, "Scan.pdf", "application/pdf", DocumentSource.SCAN)
        }
    }
    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) deliver(uri, "Document", "application/octet-stream", DocumentSource.FILE)
    }
    val digiLockerLauncher = rememberLauncherForActivityResult(DigiLockerActivity.Contract()) { result ->
        if (result == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    try {
                        result.file.readBytes()
                    } finally {
                        result.file.delete()
                    }
                }
                currentOnCaptured(CapturedFile(bytes, result.mimeType, result.fileName, DocumentSource.DIGILOCKER))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentOnError(e.message ?: "Couldn't read the DigiLocker document")
            }
        }
    }

    return remember(activity, scanLauncher, pickLauncher, digiLockerLauncher) {
        object : DocumentCaptureLauncher {
            override fun scan() {
                val host = activity ?: return currentOnError("Scanner needs an activity")
                val options = GmsDocumentScannerOptions.Builder()
                    .setGalleryImportAllowed(true)
                    .setPageLimit(MAX_SCAN_PAGES)
                    .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
                    .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                    .build()
                GmsDocumentScanning.getClient(options)
                    .getStartScanIntent(host)
                    .addOnSuccessListener { scanLauncher.launch(IntentSenderRequest.Builder(it).build()) }
                    .addOnFailureListener { currentOnError(it.message ?: "Document scanner is unavailable") }
            }

            override fun pickFile() {
                pickLauncher.launch(SUPPORTED_MIME_TYPES.toTypedArray())
            }

            override fun importFromDigiLocker() {
                digiLockerLauncher.launch(Unit)
            }
        }
    }
}

private const val MAX_SCAN_PAGES = 5

suspend fun readCapturedFile(
    context: Context,
    uri: Uri,
    fallbackName: String,
    fallbackMime: String,
    source: DocumentSource,
): CapturedFile = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    var name = fallbackName
    var size = -1L
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            cursor.getString(0)?.let { name = it }
            if (!cursor.isNull(1)) size = cursor.getLong(1)
        }
    }
    if (size > ApiRoutes.MAX_UPLOAD_BYTES) {
        error("File is larger than ${ApiRoutes.MAX_UPLOAD_BYTES / (1024 * 1024)} MB")
    }
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Couldn't open the file")
    val mimeType = sniffMimeType(bytes) ?: resolver.getType(uri) ?: fallbackMime
    if (mimeType !in SUPPORTED_MIME_TYPES) error("Only PDF, JPEG and PNG files are supported")
    if (bytes.size > ApiRoutes.MAX_UPLOAD_BYTES) {
        error("File is larger than ${ApiRoutes.MAX_UPLOAD_BYTES / (1024 * 1024)} MB")
    }
    CapturedFile(bytes, mimeType, name, source)
}

actual fun defaultServerUrl(): String = "http://10.0.2.2:8080"

actual fun sha256(bytes: ByteArray): ByteArray = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)

actual fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also { java.security.SecureRandom().nextBytes(it) }
