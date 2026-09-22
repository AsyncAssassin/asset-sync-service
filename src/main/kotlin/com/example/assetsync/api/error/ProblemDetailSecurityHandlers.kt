package com.example.assetsync.api.error

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.net.URI
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.security.access.AccessDeniedException
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
 * Security-chain counterpart of [ApiExceptionHandler]: Spring Security answers 401 before a request
 * reaches Spring MVC, so the entry point writes the same ProblemDetail shape from the filter chain.
 * The Basic challenge header is kept so `curl -u`, browsers, and API clients still know how to
 * authenticate. The detail is generic on purpose; the failure reason never reaches the client.
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
