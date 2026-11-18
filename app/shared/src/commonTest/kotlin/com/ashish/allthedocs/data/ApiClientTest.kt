package com.ashish.allthedocs.data

import com.ashish.allthedocs.api.AnalyzeScenarioResponse
import com.ashish.allthedocs.api.ApiError
import com.ashish.allthedocs.api.ApiJson
import com.ashish.allthedocs.api.ApiRoutes
import com.ashish.allthedocs.api.FollowUpQuestion
import com.ashish.allthedocs.api.VerificationRequest
import com.ashish.allthedocs.api.VerificationResult
import com.ashish.allthedocs.model.DocumentType
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ApiClientTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    private val requests = mutableListOf<HttpRequestData>()

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): ApiClient {
        val engine = MockEngine { request ->
            requests += request
            handler(request)
        }
        return ApiClient(ApiClient.createHttpClient(engine)) { "http://server.test/" }
    }

    @Test
    fun analyzePostsToAnalyzeRouteAndDecodesResponse() = runTest {
        val expected = AnalyzeScenarioResponse(
            title = "Home loan",
            countryCode = "IN",
            curatedScenarioId = "in.home_loan",
            questions = listOf(FollowUpQuestion("employment", "Employment?", listOf("Salaried", "Self-employed"))),
        )
        val api = client {
            respond(ApiJson.encodeToString(AnalyzeScenarioResponse.serializer(), expected), HttpStatusCode.OK, jsonHeaders)
        }

        val response = api.analyze("home loan")

        assertEquals(expected, response)
        assertEquals("http://server.test${ApiRoutes.ANALYZE_SCENARIO}", requests.single().url.toString())
    }

    @Test
    fun serverErrorMessageIsSurfaced() = runTest {
        val api = client {
            respond(
                ApiJson.encodeToString(ApiError.serializer(), ApiError("No LLM provider is configured")),
                HttpStatusCode.ServiceUnavailable,
                jsonHeaders,
            )
        }

        val error = assertFailsWith<ApiException> { api.analyze("adopting a dog in Norway") }
        assertEquals("No LLM provider is configured", error.message)
    }

    @Test
    fun unreachableServerMapsToFriendlyError() = runTest {
        val api = client { throw IllegalStateException("connection refused") }

        val error = assertFailsWith<ApiException> { api.analyze("anything") }
        assertTrue(error.message!!.contains("Couldn't reach the server"))
    }

    @Test
    fun verifySendsMultipartWithMetadataAndFile() = runTest {
        val result = VerificationResult(detectedType = DocumentType.PAN_CARD, matchesExpected = true, confidence = 0.9)
        val api = client {
            respond(ApiJson.encodeToString(VerificationResult.serializer(), result), HttpStatusCode.OK, jsonHeaders)
        }

        val response = api.verify(
            VerificationRequest("pan.png", "image/png", listOf(DocumentType.PAN_CARD), "Ashish Sah"),
            byteArrayOf(1, 2, 3),
        )

        assertEquals(result, response)
        val request = requests.single()
        assertEquals("http://server.test${ApiRoutes.VERIFY_DOCUMENT}", request.url.toString())
        assertTrue(request.body.contentType.toString().startsWith("multipart/form-data"))
    }
}
