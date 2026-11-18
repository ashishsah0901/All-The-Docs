package com.ashish.allthedocs

import com.ashish.allthedocs.api.ApiError
import com.ashish.allthedocs.api.ApiJson
import com.ashish.allthedocs.server.ApiException
import com.ashish.allthedocs.server.DotEnv
import com.ashish.allthedocs.server.ServerServices
import com.ashish.allthedocs.server.apiRoutes
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory

fun main() {
    System.setProperty("java.awt.headless", "true")
    val env = DotEnv.lookup()
    val port = env("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = { module(ServerServices.fromEnvironment(env)) })
        .start(wait = true)
}

fun Application.module(services: ServerServices = ServerServices.fromEnvironment()) {
    val log = LoggerFactory.getLogger("AllTheDocs")
    monitor.subscribe(ApplicationStopped) { services.close() }

    install(ContentNegotiation) {
        json(ApiJson)
    }
    install(StatusPages) {
        exception<ApiException> { call, cause ->
            call.respond(cause.status, ApiError(cause.message ?: cause.status.description))
        }
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ApiError("Invalid request body"))
        }
        exception<ContentTransformationException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ApiError("Invalid request body"))
        }
        exception<SerializationException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ApiError("Invalid request body"))
        }
        exception<Throwable> { call, cause ->
            log.error("Unhandled error on {}: {}", call.request.local.uri, cause::class.simpleName)
            call.respond(HttpStatusCode.InternalServerError, ApiError("Internal server error"))
        }
    }
    if (services.llm == null) {
        log.warn("No LLM_API_KEY/GROQ_API_KEY set: only curated scenarios work and document verification is disabled")
    }

    routing {
        get("/") {
            call.respond(mapOf("service" to "AllTheDocs"))
        }
        apiRoutes(services)
    }
}
