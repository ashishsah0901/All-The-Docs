package com.ashish.allthedocs.data

import com.ashish.allthedocs.api.AnalyzeScenarioRequest
import com.ashish.allthedocs.api.AnalyzeScenarioResponse
import com.ashish.allthedocs.api.ApiError
import com.ashish.allthedocs.api.ApiJson
import com.ashish.allthedocs.api.ApiRoutes
import com.ashish.allthedocs.api.CuratedScenariosResponse
import com.ashish.allthedocs.api.ResolveRequirementsRequest
import com.ashish.allthedocs.api.ResolveRequirementsResponse
import com.ashish.allthedocs.api.SignatureCheckResult
import com.ashish.allthedocs.api.VerificationRequest
import com.ashish.allthedocs.api.VerificationResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException

class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause)

class ApiClient(
    private val httpClient: HttpClient,
    private val baseUrl: suspend () -> String,
) {
    suspend fun curatedScenarios(): CuratedScenariosResponse =
        call { httpClient.get(url(ApiRoutes.CURATED_SCENARIOS)) }

    suspend fun analyze(description: String): AnalyzeScenarioResponse =
        call {
            httpClient.post(url(ApiRoutes.ANALYZE_SCENARIO)) {
                contentType(ContentType.Application.Json)
                setBody(AnalyzeScenarioRequest(description))
            }
        }

    suspend fun resolveRequirements(request: ResolveRequirementsRequest): ResolveRequirementsResponse =
        call {
            httpClient.post(url(ApiRoutes.RESOLVE_REQUIREMENTS)) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    suspend fun verify(request: VerificationRequest, bytes: ByteArray): VerificationResult =
        call {
            httpClient.submitFormWithBinaryData(
                url = url(ApiRoutes.VERIFY_DOCUMENT),
                formData = formData {
                    append(
                        ApiRoutes.VERIFY_PART_METADATA,
                        ApiJson.encodeToString(VerificationRequest.serializer(), request),
                        Headers.build { append(HttpHeaders.ContentType, ContentType.Application.Json.toString()) },
                    )
                    append(
                        ApiRoutes.VERIFY_PART_FILE,
                        bytes,
                        Headers.build {
                            append(HttpHeaders.ContentType, request.mimeType)
                            append(HttpHeaders.ContentDisposition, "filename=\"${request.fileName.replace("\"", "")}\"")
                        },
                    )
                },
            )
        }

    suspend fun checkSignature(fileName: String, mimeType: String, bytes: ByteArray): SignatureCheckResult =
        call {
            httpClient.submitFormWithBinaryData(
                url = url(ApiRoutes.CHECK_SIGNATURE),
                formData = formData {
                    append(
                        ApiRoutes.VERIFY_PART_FILE,
                        bytes,
                        Headers.build {
                            append(HttpHeaders.ContentType, mimeType)
                            append(HttpHeaders.ContentDisposition, "filename=\"${fileName.replace("\"", "")}\"")
                        },
                    )
                },
            )
        }

    private suspend fun url(path: String): String = baseUrl().trimEnd('/') + path

    private suspend inline fun <reified T> call(block: () -> HttpResponse): T {
        val response = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ApiException("Couldn't reach the server. Check the server URL in Settings.", e)
        }
        if (!response.status.isSuccess()) {
            val message = runCatching { ApiJson.decodeFromString(ApiError.serializer(), response.bodyAsText()).message }
                .getOrNull()
            throw ApiException(message ?: "Server error (${response.status.value})")
        }
        return try {
            response.body()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ApiException("The server sent an unexpected response.", e)
        }
    }

    companion object {
        fun createHttpClient(engine: io.ktor.client.engine.HttpClientEngine): HttpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(ApiJson) }
            install(HttpTimeout) {
                requestTimeoutMillis = 120_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 120_000
            }
        }
    }
}
