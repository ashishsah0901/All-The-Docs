package com.ashish.allthedocs.server

import com.ashish.allthedocs.api.AnalyzeScenarioRequest
import com.ashish.allthedocs.api.AnalyzeScenarioResponse
import com.ashish.allthedocs.api.ApiError
import com.ashish.allthedocs.api.ApiJson
import com.ashish.allthedocs.api.ApiRoutes
import com.ashish.allthedocs.api.CuratedScenariosResponse
import com.ashish.allthedocs.api.FollowUpAnswer
import com.ashish.allthedocs.api.RequirementOrigin
import com.ashish.allthedocs.api.ResolveRequirementsRequest
import com.ashish.allthedocs.api.ResolveRequirementsResponse
import com.ashish.allthedocs.api.SignatureCheckResult
import com.ashish.allthedocs.api.SignatureStatus
import com.ashish.allthedocs.api.VerificationRequest
import com.ashish.allthedocs.api.VerificationResult
import com.ashish.allthedocs.model.DocumentType
import com.ashish.allthedocs.module
import com.ashish.allthedocs.server.llm.LlmProvider
import com.ashish.allthedocs.server.verify.SignatureChecker
import com.ashish.allthedocs.server.verify.TestPki
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiRoutesTest {
    private val today = LocalDate(2026, 10, 15)

    private fun runApp(llm: LlmProvider?, block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        application { module(ServerServices(llm = llm, today = { today })) }
        val client = createClient { install(ContentNegotiation) { json(ApiJson) } }
        block(client)
    }

    private suspend fun HttpClient.postJson(path: String, body: Any): HttpResponse = post(path) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpClient.verify(
        metadata: VerificationRequest,
        bytes: ByteArray,
        contentType: String = metadata.mimeType,
    ): HttpResponse = submitFormWithBinaryData(
        url = ApiRoutes.VERIFY_DOCUMENT,
        formData = formData {
            append(ApiRoutes.VERIFY_PART_METADATA, ApiJson.encodeToString(VerificationRequest.serializer(), metadata))
            append(
                ApiRoutes.VERIFY_PART_FILE,
                bytes,
                Headers.build {
                    append(HttpHeaders.ContentType, contentType)
                    append(HttpHeaders.ContentDisposition, "filename=\"${metadata.fileName}\"")
                },
            )
        },
    )

    private suspend fun HttpClient.checkSignature(bytes: ByteArray, contentType: String = "application/pdf"): HttpResponse =
        submitFormWithBinaryData(
            url = ApiRoutes.CHECK_SIGNATURE,
            formData = formData {
                append(
                    ApiRoutes.VERIFY_PART_FILE,
                    bytes,
                    Headers.build {
                        append(HttpHeaders.ContentType, contentType)
                        append(HttpHeaders.ContentDisposition, "filename=\"document.pdf\"")
                    },
                )
            },
        )

    @Test
    fun signatureCheckWorksWithoutLlm() = runApp(llm = null) { client ->
        val response = client.checkSignature(TestPki.unsignedPdf())
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(SignatureStatus.NOT_SIGNED, response.body<SignatureCheckResult>().status)
    }

    @Test
    fun signatureCheckOfTestSignedPdfIsUntrustedAgainstBundledRoots() = runApp(llm = null) { client ->
        val response = client.checkSignature(TestPki.signedPdf())
        assertEquals(HttpStatusCode.OK, response.status)
        val result = response.body<SignatureCheckResult>()
        assertEquals(SignatureStatus.UNTRUSTED, result.status)
        assertEquals("DS Test Issuing Department", result.signerName)
    }

    @Test
    fun signatureCheckUsesInjectedTrustAnchors() = testApplication {
        application {
            module(ServerServices(llm = null, signatures = SignatureChecker(listOf(TestPki.root.certificate))))
        }
        val client = createClient { install(ContentNegotiation) { json(ApiJson) } }
        val result = client.checkSignature(TestPki.signedPdf()).body<SignatureCheckResult>()
        assertEquals(SignatureStatus.VALID, result.status)
    }

    @Test
    fun signatureCheckOfImageIsNotSigned() = runApp(llm = null) { client ->
        val response = client.checkSignature(TestFiles.png(), "image/png")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(SignatureStatus.NOT_SIGNED, response.body<SignatureCheckResult>().status)
    }

    @Test
    fun signatureCheckRejectsOversizedFile() = runApp(llm = null) { client ->
        val bytes = ByteArray((ApiRoutes.MAX_UPLOAD_BYTES + 1).toInt()).also {
            byteArrayOf(0x25, 0x50, 0x44, 0x46).copyInto(it)
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, client.checkSignature(bytes).status)
    }

    @Test
    fun signatureCheckWithoutFileIsBadRequest() = runApp(llm = null) { client ->
        val response = client.submitFormWithBinaryData(
            url = ApiRoutes.CHECK_SIGNATURE,
            formData = formData { append("other", "value") },
        )
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun healthReportsLlmConfiguration() = runApp(llm = null) { client ->
        val body = client.get(ApiRoutes.HEALTH).body<JsonObject>()
        assertEquals("ok", body["status"]!!.jsonPrimitive.content)
        assertFalse(body["llmConfigured"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun curatedScenariosListsAllWithQuestions() = runApp(llm = null) { client ->
        val response = client.get(ApiRoutes.CURATED_SCENARIOS)
        assertEquals(HttpStatusCode.OK, response.status)
        val scenarios = response.body<CuratedScenariosResponse>().scenarios
        assertEquals(6, scenarios.size)
        assertTrue(scenarios.all { it.countryCode == "IN" && it.title.isNotBlank() && it.summary.isNotBlank() })
        assertTrue(scenarios.single { it.id == "in.home_loan" }.questions.isNotEmpty())
    }

    @Test
    fun analyzeWithoutLlmFallsBackToKeywordMatch() = runApp(llm = null) { client ->
        val response = client.postJson(ApiRoutes.ANALYZE_SCENARIO, AnalyzeScenarioRequest("I need to renew my passport"))
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<AnalyzeScenarioResponse>()
        assertEquals("in.passport_application", body.curatedScenarioId)
        assertEquals("IN", body.countryCode)
        assertTrue(body.questions.isNotEmpty())
    }

    @Test
    fun analyzeWithoutLlmAndNoMatchIsUnavailable() = runApp(llm = null) { client ->
        val response = client.postJson(ApiRoutes.ANALYZE_SCENARIO, AnalyzeScenarioRequest("Adopting a dog in Norway"))
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue(response.body<ApiError>().message.contains("GROQ_API_KEY"))
    }

    @Test
    fun analyzeBlankDescriptionIsBadRequest() = runApp(llm = null) { client ->
        val response = client.postJson(ApiRoutes.ANALYZE_SCENARIO, AnalyzeScenarioRequest("   "))
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun analyzeUsesCuratedQuestionsWhenLlmPicksCuratedScenario() {
        val llm = FakeLlmProvider {
            """{"title":"Home loan","countryCode":"in","curatedScenarioId":"in.home_loan","questions":[{"id":"x","text":"Ignored?"}]}"""
        }
        runApp(llm) { client ->
            val body = client.postJson(ApiRoutes.ANALYZE_SCENARIO, AnalyzeScenarioRequest("Buying a flat in Pune with a loan"))
                .body<AnalyzeScenarioResponse>()
            assertEquals("in.home_loan", body.curatedScenarioId)
            assertEquals("employment_type", body.questions.first().id)
            assertTrue(llm.requests.single().jsonMode)
        }
    }

    @Test
    fun analyzeIgnoresCuratedIdForOtherCountries() {
        val llm = FakeLlmProvider {
            """{"title":"UK home loan","countryCode":"GB","curatedScenarioId":"in.home_loan","questions":[{"text":"Are you a first-time buyer?","options":["Yes","No"]}]}"""
        }
        runApp(llm) { client ->
            val body = client.postJson(ApiRoutes.ANALYZE_SCENARIO, AnalyzeScenarioRequest("Mortgage in London"))
                .body<AnalyzeScenarioResponse>()
            assertNull(body.curatedScenarioId)
            assertEquals("GB", body.countryCode)
            assertEquals("q1", body.questions.single().id)
            assertEquals(listOf("Yes", "No"), body.questions.single().options)
        }
    }

    @Test
    fun resolveCuratedAppliesAnswerConditions() = runApp(llm = null) { client ->
        val body = client.postJson(
            ApiRoutes.RESOLVE_REQUIREMENTS,
            ResolveRequirementsRequest(
                description = "Renew passport",
                title = "Passport",
                countryCode = "IN",
                curatedScenarioId = "in.passport_application",
                answers = listOf(
                    FollowUpAnswer("application_type", "Fresh or re-issue?", "Re-issue"),
                    FollowUpAnswer("applicant", "Who?", "Adult (18+)"),
                ),
            ),
        ).body<ResolveRequirementsResponse>()
        assertEquals(RequirementOrigin.CURATED, body.origin)
        val ids = body.requirements.map { it.id }
        assertTrue("old_passport" in ids)
        assertFalse("dob_proof" in ids)
        assertFalse("parental_consent" in ids)
    }

    @Test
    fun resolveUnknownCuratedIdIsBadRequest() = runApp(llm = null) { client ->
        val response = client.postJson(
            ApiRoutes.RESOLVE_REQUIREMENTS,
            ResolveRequirementsRequest("x", "x", curatedScenarioId = "nope"),
        )
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun resolveWithoutCuratedIdNeedsLlm() = runApp(llm = null) { client ->
        val response = client.postJson(ApiRoutes.RESOLVE_REQUIREMENTS, ResolveRequirementsRequest("Schengen visa", "Visa"))
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }

    @Test
    fun resolveUsesWebSearchModel() {
        val llm = FakeLlmProvider {
            """Searching【0†L1-L3】 done.
            {"title":"Schengen visa","assumptions":["Tourist visit"],"requirements":[
              {"id":"passport","name":"Passport","acceptedTypes":["passport"],"citations":[{"title":"EU","url":"https://home-affairs.ec.europa.eu"},{"title":"bad","url":"javascript:alert(1)"}]},
              {"id":"passport","name":"Travel insurance","acceptedTypes":["insurance_policy","teleporter"]}
            ]}"""
        }
        runApp(llm) { client ->
            val body = client.postJson(
                ApiRoutes.RESOLVE_REQUIREMENTS,
                ResolveRequirementsRequest("Tourist visa for Germany", "Schengen visa", countryCode = "de"),
            ).body<ResolveRequirementsResponse>()
            assertEquals(RequirementOrigin.LLM_WEB_SEARCH, body.origin)
            assertEquals("DE", body.countryCode)
            assertEquals(listOf("passport", "passport_2"), body.requirements.map { it.id })
            assertEquals(1, body.requirements[0].citations.size)
            assertEquals(listOf(DocumentType.INSURANCE_POLICY, DocumentType.OTHER), body.requirements[1].acceptedTypes)
            val request = llm.requests.single()
            assertTrue(request.webSearch)
            assertFalse(request.jsonMode)
        }
    }

    @Test
    fun resolveFallsBackToKnowledgeWhenSearchFails() {
        val llm = FakeLlmProvider { request ->
            if (request.webSearch) throw LlmUnavailableException("search down")
            """{"title":"Visa","requirements":[{"name":"Passport","acceptedTypes":["passport"]}]}"""
        }
        runApp(llm) { client ->
            val body = client.postJson(ApiRoutes.RESOLVE_REQUIREMENTS, ResolveRequirementsRequest("Visa for Japan", ""))
                .body<ResolveRequirementsResponse>()
            assertEquals(RequirementOrigin.LLM_KNOWLEDGE, body.origin)
            assertEquals("passport", body.requirements.single().id)
            assertEquals(2, llm.requests.size)
        }
    }

    @Test
    fun resolveReturnsBadGatewayWhenLlmOutputIsUnusable() {
        val llm = FakeLlmProvider { "I cannot help with that." }
        runApp(llm) { client ->
            val response = client.postJson(ApiRoutes.RESOLVE_REQUIREMENTS, ResolveRequirementsRequest("Visa for Japan", ""))
            assertEquals(HttpStatusCode.BadGateway, response.status)
        }
    }

    @Test
    fun verifyImageSendsJpegAndComputesChecks() {
        val llm = FakeLlmProvider {
            """{"documentType":"passport","holderName":"SAH ASHISH","issuingAuthority":" Ministry of External Affairs ","expiryDate":"2026-01-31","confidence":0.92,"issues":[],"summary":"An Indian passport."}"""
        }
        runApp(llm) { client ->
            val response = client.verify(
                VerificationRequest("passport.png", "image/png", listOf(DocumentType.PASSPORT), "Ashish Sah"),
                TestFiles.png(),
            )
            assertEquals(HttpStatusCode.OK, response.status)
            val result = response.body<VerificationResult>()
            assertEquals(DocumentType.PASSPORT, result.detectedType)
            assertTrue(result.matchesExpected)
            assertEquals(true, result.nameMatches)
            assertEquals(LocalDate(2026, 1, 31), result.expiryDate)
            assertEquals("Ministry of External Affairs", result.issuingAuthority)
            assertTrue(result.issues.any { it.contains("expired") })
            val image = llm.requests.single().images.single()
            assertEquals(0xFF.toByte(), image[0])
            assertEquals(0xD8.toByte(), image[1])
        }
    }

    @Test
    fun verifyPdfSendsTextAndPageImages() {
        val llm = FakeLlmProvider {
            """{"documentType":"bank_statement","holderName":null,"confidence":0.8,"summary":"A bank statement."}"""
        }
        runApp(llm) { client ->
            val result = client.verify(
                VerificationRequest("statement.pdf", "application/pdf", listOf(DocumentType.PAN_CARD)),
                TestFiles.pdf("Account statement", pages = 5),
            ).body<VerificationResult>()
            assertEquals(DocumentType.BANK_STATEMENT, result.detectedType)
            assertFalse(result.matchesExpected)
            assertNull(result.nameMatches)
            assertTrue(result.issues.isNotEmpty())
            val request = llm.requests.single()
            assertEquals(3, request.images.size)
            assertTrue(request.userText.contains("Account statement page 1"))
        }
    }

    @Test
    fun verifyRejectsUnsupportedMimeType() = runApp(FakeLlmProvider { "{}" }) { client ->
        val response = client.verify(VerificationRequest("a.gif", "image/gif"), byteArrayOf(0x47, 0x49, 0x46, 0x38))
        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
    }

    @Test
    fun verifyRejectsUnrecognisedContent() = runApp(FakeLlmProvider { "{}" }) { client ->
        val response = client.verify(VerificationRequest("a.png", "image/png"), "not an image".toByteArray())
        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
    }

    @Test
    fun verifyRejectsOversizedFile() = runApp(FakeLlmProvider { "{}" }) { client ->
        val bytes = ByteArray((ApiRoutes.MAX_UPLOAD_BYTES + 1).toInt()).also {
            byteArrayOf(0x25, 0x50, 0x44, 0x46).copyInto(it)
        }
        val response = client.verify(VerificationRequest("big.pdf", "application/pdf"), bytes)
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
    }

    @Test
    fun verifyWithoutMetadataIsBadRequest() = runApp(FakeLlmProvider { "{}" }) { client ->
        val response = client.submitFormWithBinaryData(
            url = ApiRoutes.VERIFY_DOCUMENT,
            formData = formData {
                append(
                    ApiRoutes.VERIFY_PART_FILE,
                    TestFiles.png(),
                    Headers.build {
                        append(HttpHeaders.ContentType, "image/png")
                        append(HttpHeaders.ContentDisposition, "filename=\"a.png\"")
                    },
                )
            },
        )
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun verifyWithoutLlmIsUnavailable() = runApp(llm = null) { client ->
        val response = client.verify(VerificationRequest("a.png", "image/png"), TestFiles.png())
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertNotNull(response.body<ApiError>().message)
    }
}
