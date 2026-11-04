package app.foldersync.common

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.http.converter.HttpMessageNotReadableException
import java.security.SecureRandom

/** Stable JSON error body for every `/api/...` failure. */
data class ApiError(
    val code: String,
    val message: String,
    val retryable: Boolean = false,
    val path: String? = null,
    val details: Map<String, Any?>? = null,
)

/**
 * A failure with a stable [code]. Ownership failures deliberately look exactly like absence (404 `not_found`).
 * [retryable] means the same request may succeed later; replans are signalled with `details.replan = true`.
 */
class ApiException(
    val status: HttpStatus,
    val code: String,
    message: String,
    val retryable: Boolean = false,
    val path: String? = null,
    val details: Map<String, Any?>? = null,
) : RuntimeException(message) {
    companion object {
        fun notFound(what: String = "resource") = ApiException(HttpStatus.NOT_FOUND, "not_found", "The $what was not found")
        fun invalid(code: String, message: String, path: String? = null, details: Map<String, Any?>? = null) =
            ApiException(HttpStatus.UNPROCESSABLE_CONTENT, code, message, false, path, details)
        fun bad(code: String, message: String) = ApiException(HttpStatus.BAD_REQUEST, code, message)
        fun conflict(code: String, message: String, retryable: Boolean = false, path: String? = null, details: Map<String, Any?>? = null) =
            ApiException(HttpStatus.CONFLICT, code, message, retryable, path, details)
        fun stale(message: String, details: Map<String, Any?>? = null, path: String? = null) =
            ApiException(HttpStatus.CONFLICT, "stale_plan", message, true, path, (details ?: emptyMap()) + ("replan" to true))
        fun gone(code: String, message: String, details: Map<String, Any?>? = null) =
            ApiException(HttpStatus.GONE, code, message, false, null, details)
    }
}

@RestControllerAdvice
class ApiExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiException::class)
    fun api(e: ApiException): ResponseEntity<ApiError> =
        ResponseEntity.status(e.status).body(ApiError(e.code, e.message ?: e.code, e.retryable, e.path, e.details))

    @ExceptionHandler(
        HttpMessageNotReadableException::class, MissingServletRequestParameterException::class,
        MissingRequestHeaderException::class, MethodArgumentTypeMismatchException::class,
    )
    fun malformed(e: Exception): ResponseEntity<ApiError> =
        ResponseEntity.badRequest().body(ApiError("malformed_request", "The request could not be understood"))

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun method(e: HttpRequestMethodNotSupportedException) =
        ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(ApiError("method_not_allowed", "Method not allowed"))

    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun media(e: HttpMediaTypeNotSupportedException) =
        ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(ApiError("unsupported_media_type", "Unsupported media type"))

    @ExceptionHandler(java.io.IOException::class)
    fun io(e: java.io.IOException, req: HttpServletRequest): ResponseEntity<ApiError> {
        log.error("storage failure on {}", req.requestURI, e)
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(ApiError("storage_unavailable", "Storage is temporarily unavailable", retryable = true))
    }

    @ExceptionHandler(Exception::class)
    fun unexpected(e: Exception, req: HttpServletRequest): ResponseEntity<ApiError> {
        log.error("unexpected failure on {} {}", req.method, req.requestURI, e)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiError("internal_error", "Unexpected server error", retryable = true))
    }
}

object Ids {
    private val random = SecureRandom()

    fun uuid(): String = java.util.UUID.randomUUID().toString()

    /** 128-bit random identifier used for blobs and upload tokens. Identifiers never grant access by themselves. */
    fun token(): String {
        val b = ByteArray(16)
        random.nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }
}

private val SHA256 = Regex("^[0-9a-f]{64}$")
fun isSha256(s: String?): Boolean = s != null && SHA256.matches(s)
