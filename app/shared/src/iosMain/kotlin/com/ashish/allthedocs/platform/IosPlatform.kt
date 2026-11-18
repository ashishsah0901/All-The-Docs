package com.ashish.allthedocs.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.ashish.allthedocs.api.ApiRoutes
import com.ashish.allthedocs.model.DocumentSource
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import org.koin.compose.koinInject
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDateComponents
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionComplete
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNCalendarNotificationTrigger
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNUserNotificationCenter
import platform.posix.memcpy
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

class PickedDocument(val data: NSData, val mimeType: String, val fileName: String)

interface IosPlatformBridge {
    fun seal(data: NSData): NSData?
    fun open(data: NSData): NSData?
    fun scanDocument(completion: (PickedDocument?, String?) -> Unit)
    fun pickDocument(completion: (PickedDocument?, String?) -> Unit)
    fun openDigiLocker(completion: (PickedDocument?, String?) -> Unit)
    fun authenticate(url: String, callbackScheme: String, completion: (String?, String?) -> Unit)
    fun preview(data: NSData, fileName: String)
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val out = ByteArray(size)
    if (size > 0) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}

@OptIn(ExperimentalForeignApi::class)
internal fun applicationSupportDirectory(): String {
    val url: NSURL? = NSFileManager.defaultManager.URLForDirectory(
        directory = NSApplicationSupportDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = true,
        error = null,
    )
    return requireNotNull(url?.path) { "Application Support directory unavailable" }
}

class IosSecureFileStore(private val bridge: IosPlatformBridge) : SecureFileStore {
    private val directory: String by lazy { applicationSupportDirectory() + "/vault" }

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun write(name: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val manager = NSFileManager.defaultManager
        manager.createDirectoryAtPath(directory, withIntermediateDirectories = true, attributes = null, error = null)
        val sealed = bridge.seal(bytes.toNSData()) ?: error("Couldn't encrypt the document")
        val created = manager.createFileAtPath(
            path = pathFor(name),
            contents = sealed,
            attributes = mapOf<Any?, Any?>(NSFileProtectionKey to NSFileProtectionComplete),
        )
        check(created) { "Couldn't store the document" }
    }

    override suspend fun read(name: String): ByteArray = withContext(Dispatchers.IO) {
        val sealed = NSData.dataWithContentsOfFile(pathFor(name)) ?: error("Document file is missing")
        val opened = bridge.open(sealed) ?: error("Couldn't decrypt the document")
        opened.toByteArray()
    }

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun delete(name: String) {
        withContext(Dispatchers.IO) { NSFileManager.defaultManager.removeItemAtPath(pathFor(name), error = null) }
    }

    private fun pathFor(name: String): String {
        require(NAME_PATTERN.matches(name)) { "Invalid vault file name" }
        return "$directory/$name"
    }

    private companion object {
        val NAME_PATTERN = Regex("[A-Za-z0-9-]+\\.bin")
    }
}

class IosExpiryReminderScheduler : ExpiryReminderScheduler {
    private val center get() = UNUserNotificationCenter.currentNotificationCenter()

    override suspend fun schedule(documentId: String, title: String, expiryDate: LocalDate) {
        center.requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound) { _, _ -> }
        val today = com.ashish.allthedocs.ui.today()
        REMINDER_LEAD_DAYS.forEach { leadDays ->
            val date = expiryDate.minus(DatePeriod(days = leadDays))
            if (date <= today) return@forEach
            val components = NSDateComponents().apply {
                year = date.year.toLong()
                month = date.month.ordinal.toLong() + 1
                day = date.day.toLong()
                hour = 9
                minute = 0
            }
            val content = UNMutableNotificationContent().apply {
                setTitle("$title expires in $leadDays days")
                setBody("Expiry date: $expiryDate. Start the renewal now.")
            }
            val request = UNNotificationRequest.requestWithIdentifier(
                identifier = identifier(documentId, leadDays),
                content = content,
                trigger = UNCalendarNotificationTrigger.triggerWithDateMatchingComponents(components, repeats = false),
            )
            center.addNotificationRequest(request, withCompletionHandler = null)
        }
    }

    override suspend fun cancel(documentId: String) {
        center.removePendingNotificationRequestsWithIdentifiers(REMINDER_LEAD_DAYS.map { identifier(documentId, it) })
    }

    private fun identifier(documentId: String, leadDays: Int) = "expiry-$documentId-$leadDays"
}

class IosDocumentViewer(private val bridge: IosPlatformBridge) : DocumentViewer {
    override suspend fun open(bytes: ByteArray, mimeType: String, fileName: String) {
        bridge.preview(bytes.toNSData(), fileName)
    }
}

@Composable
actual fun rememberDocumentCaptureLauncher(
    onCaptured: (CapturedFile) -> Unit,
    onError: (String) -> Unit,
): DocumentCaptureLauncher {
    val bridge = koinInject<IosPlatformBridge>()
    val currentOnCaptured by rememberUpdatedState(onCaptured)
    val currentOnError by rememberUpdatedState(onError)
    return remember(bridge) {
        fun handler(source: DocumentSource): (PickedDocument?, String?) -> Unit = { picked, error ->
            when {
                picked != null -> picked.toCapturedFile(source).fold({ currentOnCaptured(it) }, { currentOnError(it.message.orEmpty()) })
                error != null -> currentOnError(error)
            }
        }
        object : DocumentCaptureLauncher {
            override fun scan() = bridge.scanDocument(handler(DocumentSource.SCAN))
            override fun pickFile() = bridge.pickDocument(handler(DocumentSource.FILE))
            override fun importFromDigiLocker() = bridge.openDigiLocker(handler(DocumentSource.DIGILOCKER))
        }
    }
}

internal fun PickedDocument.toCapturedFile(source: DocumentSource): Result<CapturedFile> {
    if (data.length.toLong() > ApiRoutes.MAX_UPLOAD_BYTES) {
        return Result.failure(IllegalArgumentException("File is larger than ${ApiRoutes.MAX_UPLOAD_BYTES / (1024 * 1024)} MB"))
    }
    val bytes = data.toByteArray()
    val type = sniffMimeType(bytes) ?: mimeType
    if (type !in SUPPORTED_MIME_TYPES) {
        return Result.failure(IllegalArgumentException("Only PDF, JPEG and PNG files are supported"))
    }
    return Result.success(CapturedFile(bytes, type, fileName, source))
}

actual fun defaultServerUrl(): String = "http://localhost:8080"

@OptIn(ExperimentalForeignApi::class)
actual fun sha256(bytes: ByteArray): ByteArray {
    val digest = UByteArray(CC_SHA256_DIGEST_LENGTH)
    bytes.usePinned { input ->
        digest.usePinned { output ->
            CC_SHA256(if (bytes.isEmpty()) null else input.addressOf(0), bytes.size.toUInt(), output.addressOf(0))
        }
    }
    return digest.toByteArray()
}

@OptIn(ExperimentalForeignApi::class)
actual fun secureRandomBytes(size: Int): ByteArray {
    val bytes = ByteArray(size)
    if (size > 0) {
        bytes.usePinned { pinned ->
            check(SecRandomCopyBytes(kSecRandomDefault, size.toULong(), pinned.addressOf(0)) == 0) { "Secure random unavailable" }
        }
    }
    return bytes
}
