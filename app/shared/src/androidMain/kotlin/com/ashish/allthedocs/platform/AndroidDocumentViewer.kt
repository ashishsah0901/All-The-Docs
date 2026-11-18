package com.ashish.allthedocs.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AndroidDocumentViewer(private val context: Context) : DocumentViewer {
    override suspend fun open(bytes: ByteArray, mimeType: String, fileName: String) {
        val file = withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, VIEWER_DIRECTORY)
            directory.deleteRecursively()
            directory.mkdirs()
            File(directory, fileName.replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "document" })
                .apply { writeBytes(bytes) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.vaultfiles", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            throw IllegalStateException("No app on this device can open $mimeType files", e)
        }
    }

    companion object {
        const val VIEWER_DIRECTORY = "viewer"
    }
}
