package com.ashish.allthedocs.cloud

import com.ashish.allthedocs.api.ApiRoutes
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class CloudSearchService(
    private val httpClient: HttpClient,
    private val tokens: AccessTokenSource,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val noRedirectClient: HttpClient by lazy { httpClient.config { followRedirects = false } }

    suspend fun search(provider: CloudProvider, keywords: List<String>, limit: Int = 20): List<CloudFile> {
        val terms = keywords.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (terms.isEmpty() || limit <= 0) return emptyList()
        val files = when (provider) {
            CloudProvider.GMAIL -> searchGmail(terms, limit)
            CloudProvider.GOOGLE_DRIVE -> searchDrive(terms, limit)
            CloudProvider.OUTLOOK -> searchOutlook(terms, limit)
            CloudProvider.ONEDRIVE -> searchOneDrive(terms, limit)
        }
        return files
            .filter { it.sizeBytes == null || it.sizeBytes <= ApiRoutes.MAX_UPLOAD_BYTES }
            .distinctBy { Triple(it.provider, it.parentId, it.id) }
            .sortedByDescending { epochMillisOf(it.modified) }
            .take(limit)
    }

    suspend fun download(file: CloudFile): ByteArray {
        val provider = file.provider as? CloudProvider ?: throw CloudException("${file.name} isn't from an email or drive account")
        val bytes = when (provider) {
            CloudProvider.GMAIL -> {
                val messageId = file.parentId ?: throw CloudException("Gmail attachment is missing its message")
                val body = getJson(
                    provider,
                    "$GMAIL_BASE/messages/${messageId.encodeURLPathPart()}/attachments/${file.id.encodeURLPathPart()}",
                    GmailAttachmentBody.serializer(),
                )
                decodeBase64Url(body.data ?: throw CloudException("Gmail returned an empty attachment"))
            }
            CloudProvider.GOOGLE_DRIVE -> getBytes(provider, "$DRIVE_BASE/files/${file.id.encodeURLPathPart()}") {
                parameter("alt", "media")
            }
            CloudProvider.OUTLOOK -> {
                val messageId = file.parentId ?: throw CloudException("Outlook attachment is missing its message")
                getBytes(
                    provider,
                    "$GRAPH_BASE/me/messages/${messageId.encodeURLPathPart()}/attachments/${file.id.encodeURLPathPart()}/\$value",
                )
            }
            CloudProvider.ONEDRIVE -> downloadOneDrive(provider, file)
        }
        if (bytes.size > ApiRoutes.MAX_UPLOAD_BYTES) {
            throw CloudException("File is larger than ${ApiRoutes.MAX_UPLOAD_BYTES / (1024 * 1024)} MB")
        }
        return bytes
    }

    private suspend fun searchGmail(terms: List<String>, limit: Int): List<CloudFile> {
        val provider = CloudProvider.GMAIL
        val list = getJson(provider, "$GMAIL_BASE/messages", GmailMessageList.serializer()) {
            parameter("q", gmailQuery(terms))
            parameter("maxResults", limit)
        }
        val ids = list.messages.orEmpty().map { it.id }.distinct()
        val semaphore = Semaphore(MAX_CONCURRENT_REQUESTS)
        val messages = coroutineScope {
            ids.map { id ->
                async {
                    semaphore.withPermit {
                        getJson(provider, "$GMAIL_BASE/messages/${id.encodeURLPathPart()}", GmailMessage.serializer()) {
                            parameter("format", "full")
                            parameter("fields", "id,internalDate,payload")
                        }
                    }
                }
            }.awaitAll()
        }
        return messages.flatMap { message ->
            val headers = message.payload?.headers.orEmpty()
            val subject = headers.headerValue("Subject")
            val from = headers.headerValue("From")
            val context = listOfNotNull(subject, from?.let { "from $it" }).joinToString(" · ").ifBlank { null }
            val modified = message.internalDate?.toLongOrNull()?.let(::isoFromEpochMillis)
            message.payload?.attachmentParts().orEmpty().mapNotNull { part ->
                val attachmentId = part.body?.attachmentId ?: return@mapNotNull null
                val name = part.filename?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val mimeType = supportedMimeType(part.mimeType, name) ?: return@mapNotNull null
                val size = part.body?.size
                if (mimeType != PDF && size != null && size < MIN_IMAGE_BYTES) return@mapNotNull null
                CloudFile(provider, attachmentId, message.id, name, mimeType, size, context, modified)
            }
        }
    }

    private suspend fun searchDrive(terms: List<String>, limit: Int): List<CloudFile> {
        val provider = CloudProvider.GOOGLE_DRIVE
        val response = getJson(provider, "$DRIVE_BASE/files", DriveFileList.serializer()) {
            parameter("q", driveQuery(terms))
            parameter("fields", "files(id,name,mimeType,size,modifiedTime)")
            parameter("orderBy", "modifiedTime desc")
            parameter("pageSize", limit)
        }
        return response.files.orEmpty().mapNotNull { file ->
            val mimeType = supportedMimeType(file.mimeType, file.name) ?: return@mapNotNull null
            CloudFile(provider, file.id, null, file.name, mimeType, file.size?.toLongOrNull(), "Google Drive", file.modifiedTime)
        }
    }

    private suspend fun searchOutlook(terms: List<String>, limit: Int): List<CloudFile> {
        val provider = CloudProvider.OUTLOOK
        val list = getJson(provider, "$GRAPH_BASE/me/messages", GraphMessageList.serializer()) {
            graphParameter("\$search", outlookSearch(terms))
            graphParameter("\$top", limit.toString())
            graphParameter("\$select", "id,subject,receivedDateTime,from")
        }
        val semaphore = Semaphore(MAX_CONCURRENT_REQUESTS)
        return coroutineScope {
            list.value.distinctBy { it.id }.map { message ->
                async {
                    semaphore.withPermit {
                        val attachments = getJson(
                            provider,
                            "$GRAPH_BASE/me/messages/${message.id.encodeURLPathPart()}/attachments",
                            GraphAttachmentList.serializer(),
                        ) {
                            graphParameter("\$select", "id,name,contentType,size")
                        }
                        val from = message.from?.emailAddress?.let { it.name ?: it.address }
                        val context = listOfNotNull(message.subject, from?.let { "from $it" })
                            .joinToString(" · ").ifBlank { null }
                        attachments.value.mapNotNull { attachment ->
                            if (attachment.odataType != FILE_ATTACHMENT) return@mapNotNull null
                            val name = attachment.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                            val mimeType = supportedMimeType(attachment.contentType, name) ?: return@mapNotNull null
                            if (mimeType != PDF && attachment.size != null && attachment.size < MIN_IMAGE_BYTES) {
                                return@mapNotNull null
                            }
                            CloudFile(provider, attachment.id, message.id, name, mimeType, attachment.size, context, message.receivedDateTime)
                        }
                    }
                }
            }.awaitAll().flatten()
        }
    }

    private suspend fun searchOneDrive(terms: List<String>, limit: Int): List<CloudFile> {
        val provider = CloudProvider.ONEDRIVE
        return terms.flatMap { term ->
            val escaped = term.replace("'", "''").encodeURLPathPart()
            getJson(provider, "$GRAPH_BASE/me/drive/root/search(q='$escaped')", GraphDriveItemList.serializer()) {
                graphParameter("\$select", "id,name,file,size,lastModifiedDateTime,parentReference")
                graphParameter("\$top", limit.toString())
            }.value
        }.mapNotNull { item ->
            val file = item.file ?: return@mapNotNull null
            val name = item.name ?: return@mapNotNull null
            val mimeType = supportedMimeType(file.mimeType, name) ?: return@mapNotNull null
            val folder = item.parentReference?.path?.substringAfter("root:", "")?.trim('/')?.ifBlank { null }
            CloudFile(provider, item.id, null, name, mimeType, item.size, folder?.let { "OneDrive/$it" } ?: "OneDrive", item.lastModifiedDateTime)
        }
    }

    private suspend fun downloadOneDrive(provider: CloudProvider, file: CloudFile): ByteArray {
        val token = tokens.accessToken(provider.account)
        val first = request(provider) {
            noRedirectClient.get("$GRAPH_BASE/me/drive/items/${file.id.encodeURLPathPart()}/content") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
        }
        if (first.status.value in 300..399) {
            val location = first.headers[HttpHeaders.Location] ?: throw CloudException("OneDrive download link was missing")
            val target = Url(location)
            val redirected = request(provider) {
                noRedirectClient.get(target) {
                    if (target.host == Url(GRAPH_BASE).host) header(HttpHeaders.Authorization, "Bearer $token")
                }
            }
            ensureSuccess(provider, redirected)
            return redirected.body()
        }
        ensureSuccess(provider, first)
        return first.body()
    }

    private suspend fun <T> getJson(
        provider: CloudProvider,
        url: String,
        deserializer: DeserializationStrategy<T>,
        block: HttpRequestBuilder.() -> Unit = {},
    ): T {
        val token = tokens.accessToken(provider.account)
        val response = request(provider) {
            httpClient.get(url) {
                header(HttpHeaders.Authorization, "Bearer $token")
                block()
            }
        }
        ensureSuccess(provider, response)
        return try {
            json.decodeFromString(deserializer, response.bodyAsText())
        } catch (e: SerializationException) {
            throw CloudException("${provider.displayName} sent an unexpected response", e)
        } catch (e: IllegalArgumentException) {
            throw CloudException("${provider.displayName} sent an unexpected response", e)
        }
    }

    private suspend fun getBytes(
        provider: CloudProvider,
        url: String,
        block: HttpRequestBuilder.() -> Unit = {},
    ): ByteArray {
        val token = tokens.accessToken(provider.account)
        val response = request(provider) {
            httpClient.get(url) {
                header(HttpHeaders.Authorization, "Bearer $token")
                block()
            }
        }
        ensureSuccess(provider, response)
        return response.body()
    }

    private suspend fun request(provider: CloudProvider, block: suspend () -> HttpResponse): HttpResponse =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudAuthException) {
            throw e
        } catch (e: CloudException) {
            throw e
        } catch (e: Exception) {
            throw CloudException("Couldn't reach ${provider.displayName}", e)
        }

    private fun ensureSuccess(provider: CloudProvider, response: HttpResponse) {
        when {
            response.status.isSuccess() -> Unit
            response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden ->
                throw CloudAuthException("${provider.account.displayName} access expired or wasn't granted. Sign in again.")
            else -> throw CloudException("${provider.displayName} request failed (${response.status.value})")
        }
    }

    private fun HttpRequestBuilder.graphParameter(name: String, value: String) {
        url.encodedParameters.append(name, value.encodeURLParameter())
    }

    @Serializable
    private data class GmailMessageList(val messages: List<GmailMessageRef>? = null)

    @Serializable
    private data class GmailMessageRef(val id: String)

    @Serializable
    private data class GmailMessage(
        val id: String,
        val internalDate: String? = null,
        val payload: GmailPart? = null,
    )

    @Serializable
    private data class GmailPart(
        val mimeType: String? = null,
        val filename: String? = null,
        val headers: List<GmailHeader>? = null,
        val body: GmailBody? = null,
        val parts: List<GmailPart>? = null,
    ) {
        fun attachmentParts(): List<GmailPart> =
            listOfNotNull(takeIf { !filename.isNullOrBlank() && body?.attachmentId != null }) +
                parts.orEmpty().flatMap { it.attachmentParts() }
    }

    @Serializable
    private data class GmailHeader(val name: String, val value: String)

    @Serializable
    private data class GmailBody(val attachmentId: String? = null, val size: Long? = null)

    @Serializable
    private data class GmailAttachmentBody(val data: String? = null)

    @Serializable
    private data class DriveFileList(val files: List<DriveFile>? = null)

    @Serializable
    private data class DriveFile(
        val id: String,
        val name: String,
        val mimeType: String? = null,
        val size: String? = null,
        val modifiedTime: String? = null,
    )

    @Serializable
    private data class GraphMessageList(val value: List<GraphMessage> = emptyList())

    @Serializable
    private data class GraphMessage(
        val id: String,
        val subject: String? = null,
        val receivedDateTime: String? = null,
        val from: GraphRecipient? = null,
    )

    @Serializable
    private data class GraphRecipient(val emailAddress: GraphEmailAddress? = null)

    @Serializable
    private data class GraphEmailAddress(val name: String? = null, val address: String? = null)

    @Serializable
    private data class GraphAttachmentList(val value: List<GraphAttachment> = emptyList())

    @Serializable
    private data class GraphAttachment(
        val id: String,
        @SerialName("@odata.type") val odataType: String? = null,
        val name: String? = null,
        val contentType: String? = null,
        val size: Long? = null,
    )

    @Serializable
    private data class GraphDriveItemList(val value: List<GraphDriveItem> = emptyList())

    @Serializable
    private data class GraphDriveItem(
        val id: String,
        val name: String? = null,
        val file: GraphFileFacet? = null,
        val size: Long? = null,
        val lastModifiedDateTime: String? = null,
        val parentReference: GraphParentReference? = null,
    )

    @Serializable
    private data class GraphFileFacet(val mimeType: String? = null)

    @Serializable
    private data class GraphParentReference(val path: String? = null)

    companion object {
        private const val GMAIL_BASE = "https://gmail.googleapis.com/gmail/v1/users/me"
        private const val DRIVE_BASE = "https://www.googleapis.com/drive/v3"
        private const val GRAPH_BASE = "https://graph.microsoft.com/v1.0"
        private const val FILE_ATTACHMENT = "#microsoft.graph.fileAttachment"
        private const val PDF = "application/pdf"
        private const val MAX_CONCURRENT_REQUESTS = 5
        private const val MIN_IMAGE_BYTES = 10_000L

        private val extensionMimeTypes = mapOf(
            "pdf" to PDF,
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "png" to "image/png",
        )

        internal fun supportedMimeType(declared: String?, fileName: String): String? {
            val normalized = declared?.substringBefore(';')?.trim()?.lowercase()?.let { if (it == "image/jpg") "image/jpeg" else it }
            if (normalized in extensionMimeTypes.values) return normalized
            return extensionMimeTypes[fileName.substringAfterLast('.', "").lowercase()]
        }

        internal fun gmailQuery(terms: List<String>): String {
            val quoted = terms.map { "\"" + it.replace("\"", " ").trim() + "\"" }
            return "has:attachment (filename:pdf OR filename:jpg OR filename:jpeg OR filename:png) (${quoted.joinToString(" OR ")})"
        }

        internal fun driveQuery(terms: List<String>): String {
            val clauses = terms.map { term ->
                val escaped = term.replace("\\", "\\\\").replace("'", "\\'")
                "name contains '$escaped' or fullText contains '$escaped'"
            }
            return "trashed=false and (mimeType='application/pdf' or mimeType='image/jpeg' or mimeType='image/png') and (${clauses.joinToString(" or ")})"
        }

        internal fun outlookSearch(terms: List<String>): String {
            val parts = terms.map { term ->
                val cleaned = term.replace("\"", " ").replace("\\", " ").trim()
                if (cleaned.any { it.isWhitespace() }) "\\\"$cleaned\\\"" else cleaned
            }
            return "\"hasAttachments:true AND (${parts.joinToString(" OR ")})\""
        }

        @OptIn(ExperimentalEncodingApi::class)
        internal fun decodeBase64Url(data: String): ByteArray = try {
            Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(data.trim())
        } catch (e: IllegalArgumentException) {
            throw CloudException("Couldn't decode the attachment", e)
        }

        @OptIn(ExperimentalTime::class)
        private fun isoFromEpochMillis(millis: Long): String = Instant.fromEpochMilliseconds(millis).toString()

        @OptIn(ExperimentalTime::class)
        internal fun epochMillisOf(iso: String?): Long =
            iso?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() } ?: Long.MIN_VALUE

        private fun List<GmailHeader>.headerValue(name: String): String? =
            firstOrNull { it.name.equals(name, ignoreCase = true) }?.value?.trim()?.ifBlank { null }
    }
}
