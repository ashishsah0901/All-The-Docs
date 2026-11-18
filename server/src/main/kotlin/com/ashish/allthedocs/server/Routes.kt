package com.ashish.allthedocs.server

import com.ashish.allthedocs.api.AnalyzeScenarioRequest
import com.ashish.allthedocs.api.ApiJson
import com.ashish.allthedocs.api.ApiRoutes
import com.ashish.allthedocs.api.ResolveRequirementsRequest
import com.ashish.allthedocs.api.VerificationRequest
import io.ktor.http.HttpHeaders
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

@Serializable
data class HealthResponse(val status: String, val llmConfigured: Boolean)

fun Route.apiRoutes(services: ServerServices) {
    get(ApiRoutes.HEALTH) {
        call.respond(HealthResponse(status = "ok", llmConfigured = services.llm != null))
    }

    get(ApiRoutes.CURATED_SCENARIOS) {
        call.respond(services.scenarios.curatedScenarios())
    }

    post(ApiRoutes.ANALYZE_SCENARIO) {
        val request = call.receive<AnalyzeScenarioRequest>()
        call.respond(services.scenarios.analyze(request))
    }

    post(ApiRoutes.RESOLVE_REQUIREMENTS) {
        val request = call.receive<ResolveRequirementsRequest>()
        call.respond(services.scenarios.resolve(request))
    }

    post(ApiRoutes.VERIFY_DOCUMENT) {
        val upload = call.receiveUpload()
        val request = upload.metadata?.let { metadata ->
            try {
                ApiJson.decodeFromString(VerificationRequest.serializer(), metadata)
            } catch (e: SerializationException) {
                throw InvalidInputException("Invalid metadata part")
            }
        } ?: throw InvalidInputException("Missing '${ApiRoutes.VERIFY_PART_METADATA}' part")
        val bytes = upload.requireFile()
        if (request.mimeType.isNotBlank() && request.mimeType.substringBefore(';').trim().lowercase() !in SUPPORTED_MIME_TYPES) {
            throw UnsupportedFileTypeException("Only PDF, JPEG and PNG files are supported")
        }
        call.respond(services.verifier.verify(request, bytes))
    }

    post(ApiRoutes.CHECK_SIGNATURE) {
        val bytes = call.receiveUpload().requireFile()
        call.respond(withContext(Dispatchers.IO) { services.signatures.check(bytes) })
    }
}

private class Upload(val metadata: String?, val file: ByteArray?) {
    fun requireFile(): ByteArray =
        file?.takeIf { it.isNotEmpty() } ?: throw InvalidInputException("Missing '${ApiRoutes.VERIFY_PART_FILE}' part")
}

private suspend fun ApplicationCall.receiveUpload(): Upload {
    val declaredLength = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (declaredLength != null && declaredLength > ApiRoutes.MAX_UPLOAD_BYTES + MULTIPART_OVERHEAD_BYTES) {
        throw PayloadTooLargeException(tooLargeMessage())
    }
    var metadata: String? = null
    var fileBytes: ByteArray? = null
    val multipart = receiveMultipart(formFieldLimit = ApiRoutes.MAX_UPLOAD_BYTES + MULTIPART_OVERHEAD_BYTES)
    multipart.forEachPart { part ->
        try {
            when {
                part is PartData.FormItem && part.name == ApiRoutes.VERIFY_PART_METADATA -> metadata = part.value
                part is PartData.FileItem && part.name == ApiRoutes.VERIFY_PART_FILE -> {
                    val bytes = part.provider().readRemaining(ApiRoutes.MAX_UPLOAD_BYTES + 1).readByteArray()
                    if (bytes.size > ApiRoutes.MAX_UPLOAD_BYTES) throw PayloadTooLargeException(tooLargeMessage())
                    fileBytes = bytes
                }
            }
        } finally {
            part.dispose()
        }
    }
    return Upload(metadata, fileBytes)
}

private const val MULTIPART_OVERHEAD_BYTES = 64L * 1024

private val SUPPORTED_MIME_TYPES = setOf("application/pdf", "image/jpeg", "image/jpg", "image/png")

private fun tooLargeMessage() = "File is larger than ${ApiRoutes.MAX_UPLOAD_BYTES / (1024 * 1024)} MB"
