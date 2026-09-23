package com.example.assetsync.api.error

import com.example.assetsync.application.account.AccountNotFoundException
import com.example.assetsync.application.account.DuplicateAccountExternalRefException
import com.example.assetsync.application.account.DuplicateWatchedAddressException
import com.example.assetsync.application.account.InvalidWatchedAddressException
import com.example.assetsync.application.account.InvalidWatchedAddressPageException
import com.example.assetsync.application.account.UnknownWatchedAddressException
import com.example.assetsync.application.account.UnsupportedAssetException
import com.example.assetsync.application.account.UnsupportedChainException
import com.example.assetsync.application.sync.SyncQueueFullException
import com.example.assetsync.application.sync.SyncRunNotFoundException
import com.example.assetsync.application.sync.WatchedAddressByIdNotFoundException
import com.example.assetsync.application.transaction.InvalidObservedEventRequestException
import com.example.assetsync.application.transaction.ObservedTransactionConflictException
import com.example.assetsync.application.transaction.WatchedAddressNotFoundException
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import java.time.Duration
import org.springframework.dao.DataAccessException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.slf4j.LoggerFactory
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.validation.FieldError
import org.springframework.web.ErrorResponse
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.NoHandlerFoundException
import org.springframework.web.servlet.resource.NoResourceFoundException

@RestControllerAdvice
class ApiExceptionHandler {
    private val logger = LoggerFactory.getLogger(ApiExceptionHandler::class.java)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(
        exception: MethodArgumentNotValidException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> {
        val errors = exception.bindingResult.fieldErrors.map { it.toErrorMessage() }
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "validation-failed",
            title = "Validation failed",
            detail = errors.ifEmpty { listOf("Request validation failed.") }.joinToString("; "),
            request = request,
            properties = mapOf("errors" to errors),
        )
    }

    @ExceptionHandler(
        ConstraintViolationException::class,
        MethodArgumentTypeMismatchException::class,
        HttpMessageNotReadableException::class,
        MissingServletRequestParameterException::class,
    )
    fun handleInvalidRequest(
        exception: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> {
        val detail = when (exception) {
            is MethodArgumentTypeMismatchException ->
                "${exception.name} must be a valid ${exception.requiredType?.simpleName ?: "value"}."
            is HttpMessageNotReadableException ->
                "Request body is malformed or contains invalid field types."
            is MissingServletRequestParameterException ->
                "${exception.parameterName} request parameter is required."
            else ->
                "Request validation failed."
        }

        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "invalid-request",
            title = "Invalid request",
            detail = detail,
            request = request,
        )
    }

    // Framework-level routing failures below are mapped explicitly rather than through Spring Boot's
    // `spring.mvc.problemdetails.enabled` handler: that auto-configured advice carries no @Order, so
    // it would compete with this one for the validation and body-parsing exceptions handled above,
    // and it would emit `about:blank` types without the request id. Keeping one advice keeps every
    // error on the same service-owned `type` URIs.
    @ExceptionHandler(NoResourceFoundException::class, NoHandlerFoundException::class)
    fun handleUnknownRoute(
        exception: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> =
        problem(
            status = HttpStatus.NOT_FOUND,
            type = "not-found",
            title = "Resource not found",
            detail = "No resource for ${request.method} ${request.requestURI}.",
            request = request,
        )

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleMethodNotAllowed(
        exception: HttpRequestMethodNotSupportedException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> {
        val supportedMethods = exception.supportedHttpMethods.orEmpty()
        return problem(
            status = HttpStatus.METHOD_NOT_ALLOWED,
            type = "method-not-allowed",
            title = "Method not allowed",
            detail = "Request method ${exception.method} is not supported for ${request.requestURI}.",
            request = request,
            properties = mapOf("supportedMethods" to supportedMethods.map { it.name() }),
            headers = HttpHeaders().apply { allow = supportedMethods },
        )
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun handleUnsupportedMediaType(
        exception: HttpMediaTypeNotSupportedException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> =
        problem(
            status = HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            type = "unsupported-media-type",
            title = "Unsupported media type",
            detail = exception.contentType
                ?.let { "Request content type $it is not supported; use application/json." }
                ?: "Request content type is missing; use application/json.",
            request = request,
            headers = HttpHeaders().apply { accept = exception.supportedMediaTypes },
        )

    @ExceptionHandler(AccountNotFoundException::class)
    fun handleAccountNotFound(
        exception: AccountNotFoundException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> =
        problem(
            status = HttpStatus.NOT_FOUND,
            type = "not-found",
            title = "Account not found",
            detail = "Account ${exception.accountId} was not found.",
            request = request,
        )

    @ExceptionHandler(UnsupportedChainException::class)
    fun handleUnsupportedChain(
        exception: UnsupportedChainException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> =
        // Missing, disabled, and chains the active provider cannot serve are intentionally
        // indistinguishable for registration callers.
        problem(
            status = HttpStatus.NOT_FOUND,
            type = "not-found",
            title = "Unsupported chain",
            detail = "The chain is not configured, is disabled, or is not served by the active provider.",
            request = request,
        )

    @ExceptionHandler(UnsupportedAssetException::class)
    fun handleUnsupportedAsset(
        exception: UnsupportedAssetException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> =
        // Missing and disabled assets are intentionally indistinguishable, mirroring chains.
        problem(
            status = HttpStatus.NOT_FOUND,
            type = "not-found",
            title = "Unsupported asset",
            detail = "Asset configuration was not found or is disabled for the chain.",
            request = request,
            properties = mapOf(
                "chainId" to exception.chainId,
                "asset" to exception.asset,
            ),
        )

    @ExceptionHandler(WatchedAddressNotFoundException::class)
    fun handleWatchedAddressNotFound(
        exception: WatchedAddressNotFoundException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> =
        problem(
            status = HttpStatus.NOT_FOUND,
            type = "not-found",
            title = "Watched address not found",
            detail = "Active watched address was not found.",
            request = request,
            properties = mapOf(
                "chainId" to exception.chainId,
                "address" to exception.address,
                "asset" to exception.asset,
            ),
        )

    @ExceptionHandler(WatchedAddressByIdNotFoundException::class)
    fun handleWatchedAddressByIdNotFound(
        exception: WatchedAddressByIdNotFoundException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> =
        problem(
            status = HttpStatus.NOT_FOUND,
            type = "not-found",
            title = "Watched address not found",
            detail = "Active watched address ${exception.addressId} was not found.",
            request = request,
        )

    @ExceptionHandler(UnknownWatchedAddressException::class)
    fun handleUnknownWatchedAddress(
        exception: UnknownWatchedAddressException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> =
        problem(
            status = HttpStatus.NOT_FOUND,
            type = "not-found",
            title = "Watched address not found",
            detail = "Watched address ${exception.addressId} was not found.",
            request = request,
        )

    @ExceptionHandler(SyncRunNotFoundException::class)
    fun handleSyncRunNotFound(
        exception: SyncRunNotFoundException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> =
        problem(
            status = HttpStatus.NOT_FOUND,
            type = "not-found",
            title = "Sync run not found",
            detail = "Sync run ${exception.syncRunId} was not found.",
            request = request,
        )

    @ExceptionHandler(DuplicateAccountExternalRefException::class)
    fun handleDuplicateAccount(
        exception: DuplicateAccountExternalRefException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> =
        problem(
            status = HttpStatus.CONFLICT,
            type = "duplicate-account",
            title = "Duplicate account",
            detail = "Account externalRef already exists.",
            request = request,
            properties = mapOf("externalRef" to exception.externalRef),
        )

    @ExceptionHandler(DuplicateWatchedAddressException::class)
    fun handleDuplicateWatchedAddress(
        exception: DuplicateWatchedAddressException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> =
        problem(
            status = HttpStatus.CONFLICT,
            type = "duplicate-watched-address",
            title = "Duplicate watched address",
            detail = "Watched address already exists for the chain, address, and asset.",
            request = request,
            properties = mapOf(
                "chainId" to exception.chainId,
                "address" to exception.address,
                "asset" to exception.asset,
            ),
        )

    @ExceptionHandler(InvalidWatchedAddressPageException::class)
    fun handleInvalidWatchedAddressPage(
        exception: InvalidWatchedAddressPageException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> =
        problem(
            status = HttpStatus.BAD_REQUEST,
            type = "invalid-pagination",
            title = "Invalid pagination",
            detail = "Watched address pagination parameters are outside the supported bounds.",
            request = request,
            properties = mapOf(
                "page" to exception.page,
                "size" to exception.size,
                "maxPage" to exception.maxPage,
                "maxSize" to exception.maxPageSize,
            ),
        )

    @ExceptionHandler(ObservedTransactionConflictException::class)
    fun handleObservedTransactionConflict(
        exception: ObservedTransactionConflictException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> =
        problem(
            status = HttpStatus.CONFLICT,
            type = "immutable-field-conflict",
            title = "Immutable observed transaction field conflict",
            detail = "Observed transaction natural key matched an existing row, but immutable fields did not match.",
            request = request,
            properties = mapOf(
                "chainId" to exception.naturalKey.chainId,
                "txHash" to exception.naturalKey.txHash,
                "eventIndex" to exception.naturalKey.eventIndex,
                "address" to exception.naturalKey.address,
                "asset" to exception.naturalKey.asset,
                "conflictingFields" to exception.conflictingFields.map { it.name },
            ),
        )

    @ExceptionHandler(InvalidObservedEventRequestException::class)
    fun handleInvalidObservedEventRequest(
        exception: InvalidObservedEventRequestException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> =
        problem(
            status = HttpStatus.BAD_REQUEST,
            type = "invalid-request",
            title = "Invalid request",
            detail = exception.message,
            request = request,
        )

    @ExceptionHandler(InvalidWatchedAddressException::class)
    fun handleInvalidWatchedAddress(
        exception: InvalidWatchedAddressException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> =
        problem(
            status = HttpStatus.BAD_REQUEST,
            type = "invalid-request",
            title = "Invalid request",
            detail = exception.message,
            request = request,
            properties = mapOf("chainId" to exception.chainId),
        )

    @ExceptionHandler(SyncQueueFullException::class)
    fun handleSyncQueueFull(
        exception: SyncQueueFullException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> =
        problem(
            status = HttpStatus.TOO_MANY_REQUESTS,
            type = "sync-queue-full",
            title = "Sync queue full",
            detail = "Too many sync runs are queued or running; retry shortly.",
            request = request,
            properties = mapOf("maxInFlightRuns" to exception.maxInFlightRuns),
            headers = HttpHeaders().apply {
                set(HttpHeaders.RETRY_AFTER, exception.retryAfter.toRetryAfterSeconds().toString())
            },
        )

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDatabaseIntegrityFailure(
        exception: DataIntegrityViolationException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> {
        logger.warn(
            "database_integrity_violation path={} exceptionClass={} causeClass={}",
            request.requestURI,
            exception.javaClass.simpleName,
            exception.mostSpecificCause.javaClass.simpleName,
        )
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "database-constraint-violation",
            title = "Database constraint violation",
            detail = "Request violates a database constraint.",
            request = request,
        )
    }

    @ExceptionHandler(DataAccessException::class)
    fun handleDatabaseFailure(
        exception: DataAccessException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> {
        logger.error(
            "database_operation_failed path={} exceptionClass={}",
            request.requestURI,
            exception.javaClass.simpleName,
        )
        return problem(
            status = HttpStatus.SERVICE_UNAVAILABLE,
            type = "database-unavailable",
            title = "Database unavailable",
            detail = "Database operation failed.",
            request = request,
        )
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(
        exception: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> {
        // Security decisions must keep flowing to Spring Security's ExceptionTranslationFilter, which
        // turns them into 401/403; swallowing them here would misreport them as 500.
        if (exception is AccessDeniedException || exception is AuthenticationException) {
            throw exception
        }
        // Spring MVC exceptions that carry their own status (406, 413, async timeout, ...) keep it
        // instead of collapsing into 500. The explicit handlers above still win for the common cases;
        // ErrorResponse bodies are framework-curated and safe to relay.
        if (exception is ErrorResponse) {
            val status = HttpStatus.resolve(exception.statusCode.value()) ?: HttpStatus.INTERNAL_SERVER_ERROR
            return problem(
                status = status,
                type = status.name.lowercase().replace('_', '-'),
                title = exception.body.title ?: status.reasonPhrase,
                detail = exception.body.detail ?: "The request could not be handled.",
                request = request,
                headers = exception.headers,
            )
        }
        // Last-resort mapping so no failure falls through to the container's default error page. The
        // client gets a generic detail; the exception itself goes to the log with the request path.
        logger.error(
            "unhandled_request_failure path={} exceptionClass={}",
            request.requestURI,
            exception.javaClass.simpleName,
            exception,
        )
        return problem(
            status = HttpStatus.INTERNAL_SERVER_ERROR,
            type = "internal-error",
            title = "Internal server error",
            detail = "The request could not be processed.",
            request = request,
        )
    }

    private fun FieldError.toErrorMessage(): String =
        "$field: ${defaultMessage ?: "invalid value"}"

    // Retry-After takes whole seconds; round up so a sub-second delay never advertises zero.
    private fun Duration.toRetryAfterSeconds(): Long =
        maxOf(1L, toSeconds() + if (toNanosPart() > 0) 1 else 0)

    private fun problem(
        status: HttpStatus,
        type: String,
        title: String,
        detail: String,
        request: HttpServletRequest,
        properties: Map<String, Any?> = emptyMap(),
        headers: HttpHeaders? = null,
    ): ResponseEntity<ProblemDetail> {
        val problem = ProblemDetails.build(
            status = status,
            type = type,
            title = title,
            detail = detail,
            request = request,
            properties = properties,
        )
        val response = ResponseEntity.status(status)
        headers?.let { response.headers(it) }
        return response.body(problem)
    }
}
