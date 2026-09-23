package com.example.assetsync.api.error

import com.example.assetsync.application.isDatabaseFailure
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.net.URI
import org.slf4j.LoggerFactory
import org.springframework.core.NestedExceptionUtils
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.InternalAuthenticationServiceException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.stereotype.Component

const val PROBLEM_TYPE_PREFIX = "https://asset-sync-service/errors/"
const val BASIC_AUTH_REALM = "asset-sync-service"

/**
 * Builds the service-owned ProblemDetail shape shared by every error response: a stable `type`
 * URI under [PROBLEM_TYPE_PREFIX], the request path as `instance`, and the correlation id set by
 * [RequestIdFilter] when the request carries one.
 */
object ProblemDetails {

    fun build(
        status: HttpStatus,
        type: String,
        title: String,
        detail: String,
        request: HttpServletRequest,
        properties: Map<String, Any?> = emptyMap(),
    ): ProblemDetail {
        val problem = ProblemDetail.forStatusAndDetail(status, detail)
        problem.type = URI.create(PROBLEM_TYPE_PREFIX + type)
        problem.title = title
        problem.instance = URI.create(request.requestURI)
        request.getAttribute(REQUEST_ID_ATTRIBUTE)?.let { problem.setProperty("requestId", it) }
        properties.forEach(problem::setProperty)
        return problem
    }

    /**
     * The `503` for a request PostgreSQL could not serve, from Spring MVC or from the HTTP Basic
     * user store lookup. The detail is fixed; the cause goes to the log only.
     */
    fun databaseUnavailable(request: HttpServletRequest): ProblemDetail =
        build(
            status = HttpStatus.SERVICE_UNAVAILABLE,
            type = "database-unavailable",
            title = "Database unavailable",
            detail = "Database operation failed.",
            request = request,
        )

    /** The last-resort `500`: a generic detail, while the exception goes to the log. */
    fun internalError(request: HttpServletRequest): ProblemDetail =
        build(
            status = HttpStatus.INTERNAL_SERVER_ERROR,
            type = "internal-error",
            title = "Internal server error",
            detail = "The request could not be processed.",
            request = request,
        )

    /** Writes the ProblemDetail as `application/problem+json` straight to a servlet response. */
    fun write(objectMapper: ObjectMapper, response: HttpServletResponse, problem: ProblemDetail) {
        response.status = problem.status
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        response.writer.write(objectMapper.writeValueAsString(problem))
        response.writer.flush()
    }
}

/**
 * The log lines for a request that failed on the server, shared by [ApiExceptionHandler] and the
 * authentication entry point, so a database outage leaves the same signal on either path.
 */
internal object RequestFailureLog {
    private val logger = LoggerFactory.getLogger(ApiExceptionHandler::class.java)

    fun database(request: HttpServletRequest, exception: Throwable) {
        logger.error(
            "database_operation_failed path={} exceptionClass={} causeClass={}",
            request.requestURI,
            exception.javaClass.simpleName,
            NestedExceptionUtils.getMostSpecificCause(exception).javaClass.simpleName,
        )
    }

    fun unhandled(request: HttpServletRequest, exception: Throwable) {
        logger.error(
            "unhandled_request_failure path={} exceptionClass={}",
            request.requestURI,
            exception.javaClass.simpleName,
            exception,
        )
    }
}

/**
 * Security-chain counterpart of [ApiExceptionHandler]: Spring Security answers 401 before a request
 * reaches Spring MVC, so the entry point writes the same ProblemDetail shape from the filter chain.
 * The Basic challenge header is kept so `curl -u`, browsers, and API clients still know how to
 * authenticate. The detail is generic on purpose; the failure reason never reaches the client.
 *
 * An [InternalAuthenticationServiceException] means the user store lookup itself failed, so the
 * credentials were never checked. A database failure gets the API's `503 database-unavailable` and
 * anything else the generic `500`, both without a challenge, which would blame the caller. The
 * exception is a username the store cannot even hold, such as one with a NUL character: PostgreSQL
 * refuses it as an integrity violation, and it is a bad credential like any other.
 */
@Component
class ProblemDetailAuthenticationEntryPoint(
    private val objectMapper: ObjectMapper,
) : AuthenticationEntryPoint {

    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        if (authException is InternalAuthenticationServiceException &&
            authException.cause !is DataIntegrityViolationException
        ) {
            ProblemDetails.write(objectMapper, response, userStoreFailure(request, authException))
            return
        }
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"$BASIC_AUTH_REALM\"")
        ProblemDetails.write(
            objectMapper = objectMapper,
            response = response,
            problem = ProblemDetails.build(
                status = HttpStatus.UNAUTHORIZED,
                type = "unauthorized",
                title = "Authentication required",
                detail = "Valid HTTP Basic credentials are required.",
                request = request,
            ),
        )
    }

    private fun userStoreFailure(
        request: HttpServletRequest,
        exception: InternalAuthenticationServiceException,
    ): ProblemDetail {
        val cause = exception.cause
        if (cause != null && cause.isDatabaseFailure()) {
            RequestFailureLog.database(request, cause)
            return ProblemDetails.databaseUnavailable(request)
        }
        RequestFailureLog.unhandled(request, exception)
        return ProblemDetails.internalError(request)
    }
}

/** Writes 403 as a ProblemDetail when an authenticated caller lacks the required role. */
@Component
class ProblemDetailAccessDeniedHandler(
    private val objectMapper: ObjectMapper,
) : AccessDeniedHandler {

    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) {
        ProblemDetails.write(
            objectMapper = objectMapper,
            response = response,
            problem = ProblemDetails.build(
                status = HttpStatus.FORBIDDEN,
                type = "forbidden",
                title = "Access denied",
                detail = "The authenticated caller does not have the required role.",
                request = request,
            ),
        )
    }
}
