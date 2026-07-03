package com.example.assetsync.api.error

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.util.UUID
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

const val REQUEST_ID_HEADER = "X-Request-Id"
const val REQUEST_ID_ATTRIBUTE = "assetSync.requestId"

// Runs before Spring Security (order -100) so 401/403 responses and their log lines carry the id.
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestIdFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        // Accept a caller-supplied id only if it is bounded and safe (length + charset); otherwise
        // generate one. Prevents an unbounded/hostile header being echoed into responses and logs.
        val requestId = request.getHeader(REQUEST_ID_HEADER)
            ?.trim()
            ?.takeIf { candidate -> candidate.length in 1..MAX_REQUEST_ID_LENGTH && candidate.all(::isAllowedRequestIdChar) }
            ?: UUID.randomUUID().toString()

        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId)
        response.setHeader(REQUEST_ID_HEADER, requestId)
        MDC.put("requestId", requestId)
        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove("requestId")
        }
    }

    private fun isAllowedRequestIdChar(char: Char): Boolean =
        char.isLetterOrDigit() || char == '-' || char == '_'

    private companion object {
        const val MAX_REQUEST_ID_LENGTH = 128
    }
}
