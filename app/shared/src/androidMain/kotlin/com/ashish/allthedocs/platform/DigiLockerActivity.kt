package com.ashish.allthedocs.platform

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.ashish.allthedocs.api.ApiRoutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class DigiLockerActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private var loadProgress by mutableIntStateOf(0)
    private var status by mutableStateOf<String?>(null)
    private var importing by mutableStateOf(false)

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        webView = createWebView()
        if (savedInstanceState == null) webView.loadUrl(START_URL) else webView.restoreState(savedInstanceState)

        setContent {
            MaterialTheme {
                BackHandler {
                    if (webView.canGoBack()) webView.goBack() else finish()
                }
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("DigiLocker") },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.Default.Close, contentDescription = "Close")
                                }
                            },
                            actions = { TextButton(onClick = ::signOut) { Text("Sign out") } },
                        )
                    },
                ) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                            Text(
                                status ?: "Sign in to DigiLocker yourself, open a document and tap Download. " +
                                    "It comes straight into AllTheDocs; your login details never reach the app.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        if (importing || loadProgress in 1..99) {
                            LinearProgressIndicator(
                                progress = { if (importing) 1f else loadProgress / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView = WebView(this).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.setSupportMultipleWindows(false)
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.scheme != "https"

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                loadProgress = 1
            }

            override fun onPageFinished(view: WebView, url: String?) {
                loadProgress = 100
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                loadProgress = newProgress
            }
        }
        addJavascriptInterface(BlobBridge(), BLOB_BRIDGE)
        setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            onDownload(url, userAgent, contentDisposition, mimeType)
        }
    }

    private fun onDownload(url: String, userAgent: String, contentDisposition: String?, mimeType: String?) {
        if (!isTrustedHost(webView.url)) {
            status = "Downloads are only imported from DigiLocker pages."
            return
        }
        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        importing = true
        status = "Importing $fileName…"
        when {
            url.startsWith("blob:") -> webView.evaluateJavascript(blobScript(url, fileName), null)
            url.startsWith("data:") -> deliverDataUrl(url, fileName)
            url.startsWith("https://") && isTrustedHost(url) -> lifecycleScope.launch {
                try {
                    val bytes = withContext(Dispatchers.IO) { fetch(url, userAgent) }
                    deliver(bytes, mimeType, fileName)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    fail(e.message ?: "Couldn't download the document")
                }
            }
            else -> fail("Downloads are only imported from DigiLocker.")
        }
    }

    private fun fetch(url: String, userAgent: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.setRequestProperty("User-Agent", userAgent)
            CookieManager.getInstance().getCookie(url)?.let { connection.setRequestProperty("Cookie", it) }
            webView.url?.let { connection.setRequestProperty("Referer", it) }
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            check(connection.responseCode in 200..299) { "DigiLocker returned ${connection.responseCode}" }
            return connection.inputStream.use { input -> readLimited(input) }
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            check(output.size() <= ApiRoutes.MAX_UPLOAD_BYTES) { tooLarge() }
        }
        return output.toByteArray()
    }

    private fun deliverDataUrl(dataUrl: String, fileName: String) {
        val header = dataUrl.substringBefore(',')
        val payload = dataUrl.substringAfter(',', "")
        if (!header.endsWith(";base64") || payload.isEmpty()) return fail("Couldn't read the download")
        val bytes = try {
            Base64.decode(payload, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            return fail("Couldn't read the download")
        }
        deliver(bytes, header.removePrefix("data:").removeSuffix(";base64"), fileName)
    }

    private fun deliver(bytes: ByteArray, declaredMime: String?, fileName: String) {
        if (bytes.size > ApiRoutes.MAX_UPLOAD_BYTES) return fail(tooLarge())
        val mimeType = sniffMimeType(bytes) ?: declaredMime?.substringBefore(';')?.trim()?.lowercase()
        if (mimeType !in SUPPORTED_MIME_TYPES) return fail("Only PDF, JPEG and PNG documents can be imported.")
        val directory = File(cacheDir, INCOMING_DIRECTORY).apply { mkdirs() }
        val file = File(directory, UUID.randomUUID().toString())
        file.writeBytes(bytes)
        setResult(
            Activity.RESULT_OK,
            Intent()
                .putExtra(EXTRA_PATH, file.absolutePath)
                .putExtra(EXTRA_MIME, mimeType)
                .putExtra(EXTRA_NAME, fileName),
        )
        finish()
    }

    private fun fail(message: String) {
        importing = false
        status = message
    }

    private fun signOut() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        webView.clearCache(true)
        webView.clearHistory()
        status = "Signed out of DigiLocker."
        webView.loadUrl(START_URL)
    }

    private fun tooLarge() = "Document is larger than ${ApiRoutes.MAX_UPLOAD_BYTES / (1024 * 1024)} MB"

    private inner class BlobBridge {
        @JavascriptInterface
        fun onBlob(dataUrl: String, fileName: String) {
            runOnUiThread {
                if (isTrustedHost(webView.url)) deliverDataUrl(dataUrl, fileName) else fail("Downloads are only imported from DigiLocker.")
            }
        }

        @JavascriptInterface
        fun onError(message: String) {
            runOnUiThread { fail(message.take(200)) }
        }
    }

    class Contract : ActivityResultContract<Unit, Result?>() {
        override fun createIntent(context: Context, input: Unit): Intent = Intent(context, DigiLockerActivity::class.java)

        override fun parseResult(resultCode: Int, intent: Intent?): Result? {
            if (resultCode != Activity.RESULT_OK || intent == null) return null
            val path = intent.getStringExtra(EXTRA_PATH) ?: return null
            val mime = intent.getStringExtra(EXTRA_MIME) ?: return null
            return Result(File(path), mime, intent.getStringExtra(EXTRA_NAME) ?: "DigiLocker document.pdf")
        }
    }

    class Result(val file: File, val mimeType: String, val fileName: String)

    companion object {
        const val START_URL = "https://www.digilocker.gov.in/"
        private const val BLOB_BRIDGE = "AllTheDocsBlob"
        private const val INCOMING_DIRECTORY = "incoming"
        private const val EXTRA_PATH = "path"
        private const val EXTRA_MIME = "mime"
        private const val EXTRA_NAME = "name"
        private val TRUSTED_HOST_SUFFIXES = listOf("digilocker.gov.in", "digitallocker.gov.in", "meripehchaan.gov.in")

        fun isTrustedHost(url: String?): Boolean {
            val uri = url?.let(Uri::parse) ?: return false
            val host = uri.host?.lowercase() ?: return false
            return uri.scheme == "https" && TRUSTED_HOST_SUFFIXES.any { host == it || host.endsWith(".$it") }
        }

        private fun blobScript(url: String, fileName: String): String {
            val quotedUrl = JSONObject.quote(url)
            val quotedName = JSONObject.quote(fileName)
            return """
                (function() {
                  var xhr = new XMLHttpRequest();
                  xhr.open('GET', $quotedUrl, true);
                  xhr.responseType = 'blob';
                  xhr.onload = function() {
                    var reader = new FileReader();
                    reader.onloadend = function() { $BLOB_BRIDGE.onBlob(reader.result, $quotedName); };
                    reader.readAsDataURL(xhr.response);
                  };
                  xhr.onerror = function() { $BLOB_BRIDGE.onError("Couldn't read the download"); };
                  xhr.send();
                })();
            """.trimIndent()
        }
    }
}
