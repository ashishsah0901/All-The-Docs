package com.ashish.allthedocs.server

import io.ktor.http.HttpStatusCode

open class ApiException(val status: HttpStatusCode, message: String) : RuntimeException(message)

class InvalidInputException(message: String) : ApiException(HttpStatusCode.BadRequest, message)

class PayloadTooLargeException(message: String) : ApiException(HttpStatusCode.PayloadTooLarge, message)

class UnsupportedFileTypeException(message: String) : ApiException(HttpStatusCode.UnsupportedMediaType, message)

class LlmUnavailableException(message: String) : ApiException(HttpStatusCode.ServiceUnavailable, message)

class LlmOutputException(message: String) : ApiException(HttpStatusCode.BadGateway, message)
