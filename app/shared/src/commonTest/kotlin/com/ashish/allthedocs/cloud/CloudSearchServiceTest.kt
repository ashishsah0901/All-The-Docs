package com.ashish.allthedocs.cloud

import com.ashish.allthedocs.data.ApiClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudSearchServiceTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    private val requests = mutableListOf<HttpRequestData>()
    private val tokenRequests = mutableListOf<CloudAccount>()

    private fun service(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): CloudSearchService {
        val engine = MockEngine { request ->
            requests += request
            handler(request)
        }
        return CloudSearchService(ApiClient.createHttpClient(engine)) { account ->
            tokenRequests += account
            "token-${account.name.lowercase()}"
        }
    }

    private fun MockRequestHandleScope.json(body: String) = respond(body, HttpStatusCode.OK, jsonHeaders)

    private fun HttpRequestData.param(name: String): String? = url.parameters[name]

    private fun HttpRequestData.rawQueryParam(name: String): String? =
        url.encodedQuery.split('&').map { it.split('=', limit = 2) }
            .firstOrNull { it[0] == name }?.getOrNull(1)?.decodeURLQueryComponent(plusIsSpace = true)

    @Test
    fun gmailBuildsQueryParsesNestedPartsAndFiltersUnsupported() = runTest {
        val service = service { request ->
            when (request.url.encodedPath) {
                "/gmail/v1/users/me/messages" -> json("""{"messages":[{"id":"m1"},{"id":"m2"},{"id":"m1"}]}""")
                "/gmail/v1/users/me/messages/m1" -> json(
                    """
                    {"id":"m1","internalDate":"1760000000000","payload":{"mimeType":"multipart/mixed",
                     "headers":[{"name":"Subject","value":"Your e-PAN"},{"name":"From","value":"NSDL <noreply@nsdl.com>"}],
                     "parts":[
                       {"mimeType":"multipart/alternative","parts":[{"mimeType":"text/plain","filename":"","body":{"size":10}}]},
                       {"mimeType":"application/octet-stream","filename":"ePAN.pdf","body":{"attachmentId":"a1","size":51200}},
                       {"mimeType":"application/zip","filename":"other.zip","body":{"attachmentId":"a2","size":100}},
                       {"mimeType":"image/png","filename":"logo.png","body":{"attachmentId":"a3","size":800}}
                     ]}}
                    """.trimIndent(),
                )
                "/gmail/v1/users/me/messages/m2" -> json(
                    """
                    {"id":"m2","internalDate":"1770000000000","payload":{"headers":[{"name":"Subject","value":"Scan"}],
                     "parts":[{"mimeType":"image/jpeg","filename":"pan-scan.jpg","body":{"attachmentId":"b1","size":200000}}]}}
                    """.trimIndent(),
                )
                else -> error("unexpected ${request.url}")
            }
        }

        val files = service.search(CloudProvider.GMAIL, listOf("PAN card", "e-PAN"), limit = 10)

        val list = requests.first()
        assertEquals(
            "has:attachment (filename:pdf OR filename:jpg OR filename:jpeg OR filename:png) (\"PAN card\" OR \"e-PAN\")",
            list.param("q"),
        )
        assertEquals("10", list.param("maxResults"))
        assertEquals("Bearer token-google", list.headers[HttpHeaders.Authorization])
        val detail = requests.first { it.url.encodedPath.endsWith("/m1") }
        assertEquals("full", detail.param("format"))
        assertEquals("id,internalDate,payload", detail.param("fields"))
        assertEquals(3, requests.size)

        assertEquals(listOf("pan-scan.jpg", "ePAN.pdf"), files.map { it.name })
        val pdf = files.last()
        assertEquals("a1", pdf.id)
        assertEquals("m1", pdf.parentId)
        assertEquals("application/pdf", pdf.mimeType)
        assertEquals(51200L, pdf.sizeBytes)
        assertEquals("Your e-PAN · from NSDL <noreply@nsdl.com>", pdf.context)
        assertTrue(pdf.modified!!.startsWith("2025-10-09T"))
    }

    @Test
    fun gmailDownloadDecodesUnpaddedBase64Url() = runTest {
        val service = service { request ->
            assertEquals("/gmail/v1/users/me/messages/m1/attachments/a1", request.url.encodedPath)
            json("""{"size":5,"data":"-_8-_w"}""")
        }

        val bytes = service.download(CloudFile(CloudProvider.GMAIL, "a1", "m1", "x.pdf", "application/pdf", 4, null, null))

        assertContentEquals(byteArrayOf(0xFB.toByte(), 0xFF.toByte(), 0x3E, 0xFF.toByte()), bytes)
    }

    @Test
    fun driveBuildsEscapedQueryAndParses() = runTest {
        val service = service {
            json(
                """
                {"files":[
                  {"id":"d1","name":"Form16_FY25.pdf","mimeType":"application/pdf","size":"12345","modifiedTime":"2026-05-01T10:00:00.000Z"},
                  {"id":"d2","name":"notes.docx","mimeType":"application/vnd.openxmlformats-officedocument.wordprocessingml.document","size":"10"},
                  {"id":"d3","name":"huge.pdf","mimeType":"application/pdf","size":"99999999","modifiedTime":"2026-06-01T10:00:00.000Z"}
                ]}
                """.trimIndent(),
            )
        }

        val files = service.search(CloudProvider.GOOGLE_DRIVE, listOf("Form 16", "O'Brien\\x"))

        val request = requests.single()
        assertEquals("/drive/v3/files", request.url.encodedPath)
        assertEquals(
            "trashed=false and (mimeType='application/pdf' or mimeType='image/jpeg' or mimeType='image/png') and " +
                "(name contains 'Form 16' or fullText contains 'Form 16' or name contains 'O\\'Brien\\\\x' or fullText contains 'O\\'Brien\\\\x')",
            request.param("q"),
        )
        assertEquals("files(id,name,mimeType,size,modifiedTime)", request.param("fields"))
        assertEquals("modifiedTime desc", request.param("orderBy"))
        assertEquals("20", request.param("pageSize"))
        assertEquals("Bearer token-google", request.headers[HttpHeaders.Authorization])
        assertEquals(listOf("d1"), files.map { it.id })
        assertEquals(12345L, files.single().sizeBytes)
        assertNull(files.single().parentId)
    }

    @Test
    fun driveDownloadUsesAltMedia() = runTest {
        val service = service { request ->
            assertEquals("/drive/v3/files/d1", request.url.encodedPath)
            assertEquals("media", request.param("alt"))
            respond(byteArrayOf(1, 2, 3), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/pdf"))
        }

        val bytes = service.download(CloudFile(CloudProvider.GOOGLE_DRIVE, "d1", null, "f.pdf", "application/pdf", 3, null, null))

        assertContentEquals(byteArrayOf(1, 2, 3), bytes)
    }

    @Test
    fun outlookBuildsKqlSearchAndKeepsFileAttachments() = runTest {
        val service = service { request ->
            when (request.url.encodedPath) {
                "/v1.0/me/messages" -> json(
                    """
                    {"value":[{"id":"o1","subject":"Offer letter","receivedDateTime":"2026-03-02T09:00:00Z",
                      "from":{"emailAddress":{"name":"HR Team","address":"hr@example.com"}}}]}
                    """.trimIndent(),
                )
                "/v1.0/me/messages/o1/attachments" -> json(
                    """
                    {"value":[
                      {"@odata.type":"#microsoft.graph.fileAttachment","id":"att1","name":"Offer.pdf","contentType":"application/pdf","size":40000},
                      {"@odata.type":"#microsoft.graph.itemAttachment","id":"att2","name":"Meeting","contentType":null,"size":10},
                      {"@odata.type":"#microsoft.graph.fileAttachment","id":"att3","name":"photo.heic","contentType":"image/heic","size":40000}
                    ]}
                    """.trimIndent(),
                )
                else -> error("unexpected ${request.url}")
            }
        }

        val files = service.search(CloudProvider.OUTLOOK, listOf("offer letter", "appointment"), limit = 15)

        val list = requests.first()
        assertEquals("\"hasAttachments:true AND (\\\"offer letter\\\" OR appointment)\"", list.rawQueryParam("\$search"))
        assertEquals("15", list.rawQueryParam("\$top"))
        assertEquals("id,subject,receivedDateTime,from", list.rawQueryParam("\$select"))
        assertTrue(list.url.encodedQuery.contains("\$search="))
        assertEquals("Bearer token-microsoft", list.headers[HttpHeaders.Authorization])
        assertEquals("id,name,contentType,size", requests[1].rawQueryParam("\$select"))

        val file = files.single()
        assertEquals("att1", file.id)
        assertEquals("o1", file.parentId)
        assertEquals("Offer letter · from HR Team", file.context)
        assertEquals("2026-03-02T09:00:00Z", file.modified)
    }

    @Test
    fun outlookDownloadUsesValuePath() = runTest {
        val service = service { request ->
            assertEquals("/v1.0/me/messages/o1/attachments/att1/\$value", request.url.encodedPath)
            respond(byteArrayOf(9), HttpStatusCode.OK)
        }

        val bytes = service.download(CloudFile(CloudProvider.OUTLOOK, "att1", "o1", "Offer.pdf", "application/pdf", 1, null, null))

        assertContentEquals(byteArrayOf(9), bytes)
    }

    @Test
    fun oneDriveSearchesEachKeywordAndMerges() = runTest {
        val service = service { request ->
            val path = request.url.encodedPath
            when {
                path.contains("bank") -> json(
                    """
                    {"value":[
                      {"id":"i1","name":"HDFC statement.pdf","file":{"mimeType":"application/pdf"},"size":5000,
                       "lastModifiedDateTime":"2026-01-01T00:00:00Z","parentReference":{"path":"/drive/root:/Finance"}},
                      {"id":"f1","name":"Finance","folder":{"childCount":3}}
                    ]}
                    """.trimIndent(),
                )
                else -> json(
                    """
                    {"value":[
                      {"id":"i1","name":"HDFC statement.pdf","file":{"mimeType":"application/pdf"},"size":5000,
                       "lastModifiedDateTime":"2026-01-01T00:00:00Z","parentReference":{"path":"/drive/root:/Finance"}},
                      {"id":"i2","name":"stmt.png","file":{"mimeType":"image/png"},"size":60000,"lastModifiedDateTime":"2026-02-01T00:00:00Z"}
                    ]}
                    """.trimIndent(),
                )
            }
        }

        val files = service.search(CloudProvider.ONEDRIVE, listOf("bank statement", "O'Neil"))

        assertEquals(2, requests.size)
        assertEquals("/v1.0/me/drive/root/search(q='bank%20statement')", requests[0].url.encodedPath)
        assertEquals("/v1.0/me/drive/root/search(q='O''Neil')", requests[1].url.encodedPath)
        assertEquals("id,name,file,size,lastModifiedDateTime,parentReference", requests[0].rawQueryParam("\$select"))
        assertEquals(listOf("i2", "i1"), files.map { it.id })
        assertEquals("OneDrive/Finance", files.last().context)
        assertEquals("OneDrive", files.first().context)
    }

    @Test
    fun oneDriveDownloadDoesNotForwardTokenToRedirectHost() = runTest {
        val service = service { request ->
            when (request.url.host) {
                "graph.microsoft.com" -> {
                    assertEquals("/v1.0/me/drive/items/i1/content", request.url.encodedPath)
                    assertEquals("Bearer token-microsoft", request.headers[HttpHeaders.Authorization])
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://public.dm.files.1drv.com/y4m/file.pdf?download=1"))
                }
                "public.dm.files.1drv.com" -> {
                    assertNull(request.headers[HttpHeaders.Authorization])
                    respond(byteArrayOf(7, 7), HttpStatusCode.OK)
                }
                else -> error("unexpected ${request.url}")
            }
        }

        val bytes = service.download(CloudFile(CloudProvider.ONEDRIVE, "i1", null, "a.pdf", "application/pdf", 2, null, null))

        assertContentEquals(byteArrayOf(7, 7), bytes)
        assertEquals(2, requests.size)
    }

    @Test
    fun unauthorizedBecomesAuthException() = runTest {
        val service = service { respond("""{"error":{"message":"secret details"}}""", HttpStatusCode.Unauthorized, jsonHeaders) }

        val error = assertFailsWith<CloudAuthException> { service.search(CloudProvider.GOOGLE_DRIVE, listOf("pan")) }

        assertTrue(error.message!!.contains("Sign in again"))
        assertTrue(!error.message!!.contains("secret"))
    }

    @Test
    fun forbiddenBecomesAuthException() = runTest {
        val service = service { respond("{}", HttpStatusCode.Forbidden, jsonHeaders) }

        assertFailsWith<CloudAuthException> { service.search(CloudProvider.OUTLOOK, listOf("pan")) }
    }

    @Test
    fun serverErrorBecomesCloudException() = runTest {
        val service = service { respond("oops", HttpStatusCode.InternalServerError) }

        val error = assertFailsWith<CloudException> { service.search(CloudProvider.GMAIL, listOf("pan")) }

        assertEquals("Gmail request failed (500)", error.message)
    }

    @Test
    fun malformedResponseBecomesCloudException() = runTest {
        val service = service { json("""{"files": "nope"}""") }

        val error = assertFailsWith<CloudException> { service.search(CloudProvider.GOOGLE_DRIVE, listOf("pan")) }

        assertEquals("Google Drive sent an unexpected response", error.message)
    }

    @Test
    fun blankKeywordsSkipNetwork() = runTest {
        val service = service { error("should not be called") }

        assertEquals(emptyList(), service.search(CloudProvider.GMAIL, listOf(" ", "")))
        assertEquals(emptyList(), tokenRequests)
    }

    @Test
    fun mimeTypeFallsBackToExtension() {
        assertEquals("image/jpeg", CloudSearchService.supportedMimeType("image/jpg", "a"))
        assertEquals("application/pdf", CloudSearchService.supportedMimeType("application/octet-stream", "X.PDF"))
        assertEquals(null, CloudSearchService.supportedMimeType("application/zip", "a.zip"))
    }
}
