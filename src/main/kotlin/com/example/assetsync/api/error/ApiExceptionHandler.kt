package com.example.assetsync.api.error

import com.example.assetsync.application.account.AccountNotFoundException
import com.example.assetsync.application.account.DuplicateAccountExternalRefException
import com.example.assetsync.application.account.DuplicateWatchedAddressException
import com.example.assetsync.application.account.UnsupportedChainException
import com.example.assetsync.application.sync.SyncProviderUnavailableException
import com.example.assetsync.application.sync.SyncRunNotFoundException
import com.example.assetsync.application.sync.WatchedAddressByIdNotFoundException
import com.example.assetsync.application.transaction.InvalidObservedEventRequestException
import com.example.assetsync.application.transaction.ObservedTransactionConflictException
import com.example.assetsync.application.transaction.WatchedAddressNotFoundException
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import java.net.URI
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.slf4j.LoggerFactory
import org.springframework.validation.FieldError
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

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
        // Missing and disabled chains are intentionally indistinguishable for registration callers.
        problem(
            status = HttpStatus.NOT_FOUND,
            type = "not-found",
            title = "Unsupported chain",
            detail = "Chain configuration was not found or is disabled.",
            request = request,
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

    @ExceptionHandler(SyncProviderUnavailableException::class)
    fun handleProviderUnavailable(
        exception: SyncProviderUnavailableException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> =
        problem(
            status = HttpStatus.SERVICE_UNAVAILABLE,
            type = "provider-unavailable",
            title = "Provider unavailable",
            detail = "Provider operation failed.",
            request = request,
            properties = mapOf(
                "syncRunId" to exception.syncRun.id,
                "targetType" to exception.syncRun.targetType.name,
                "targetId" to exception.syncRun.targetId,
            ),
        )

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

    private fun FieldError.toErrorMessage(): String =
        "$field: ${defaultMessage ?: "invalid value"}"

    private fun problem(
        status: HttpStatus,
        type: String,
        title: String,
        detail: String,
        request: HttpServletRequest,
        properties: Map<String, Any?> = emptyMap(),
    ): ResponseEntity<ProblemDetail> {
        val problem = ProblemDetail.forStatusAndDetail(status, detail)
        problem.type = URI.create("https://asset-sync-service/errors/$type")
        problem.title = title
        problem.instance = URI.create(request.requestURI)
        properties.forEach(problem::setProperty)
        return ResponseEntity.status(status).body(problem)
    }
}
