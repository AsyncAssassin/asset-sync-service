package com.example.assetsync.unit

import com.example.assetsync.api.error.ProblemDetailAccessDeniedHandler
import com.example.assetsync.api.error.ProblemDetailAuthenticationEntryPoint
import com.example.assetsync.api.error.REQUEST_ID_ATTRIBUTE
import com.fasterxml.jackson.databind.ObjectMapper
import java.sql.SQLTransientConnectionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
import org.springframework.jdbc.CannotGetJdbcConnectionException
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.InternalAuthenticationServiceException

/**
 * The security handlers run outside Spring MVC, so they are exercised directly against mock
 * servlet objects. The ObjectMapper comes from the same builder Spring Boot uses, which registers
 * the ProblemDetail mixin that flattens custom properties such as `requestId`.
 */
class ProblemDetailSecurityHandlersTests {

    private val objectMapper: ObjectMapper = Jackson2ObjectMapperBuilder.json().build()

    @Test
    fun `authentication entry point writes a 401 problem detail and keeps the basic challenge`() {
        val request = MockHttpServletRequest("GET", "/api/v1/accounts").apply {
            setAttribute(REQUEST_ID_ATTRIBUTE, "req-401")
        }
        val response = MockHttpServletResponse()

        ProblemDetailAuthenticationEntryPoint(objectMapper)
            .commence(request, response, BadCredentialsException("secret reason"))

        assertEquals(401, response.status)
        assertTrue(MediaType.parseMediaType(response.contentType!!).isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        assertEquals("Basic realm=\"asset-sync-service\"", response.getHeader(HttpHeaders.WWW_AUTHENTICATE))
        val body = objectMapper.readTree(response.contentAsString)
        assertEquals("https://asset-sync-service/errors/unauthorized", body["type"].asText())
        assertEquals("Authentication required", body["title"].asText())
        assertEquals(401, body["status"].asInt())
        assertEquals("/api/v1/accounts", body["instance"].asText())
        assertEquals("req-401", body["requestId"].asText())
        assertFalse(response.contentAsString.contains("secret reason"), "the authentication failure reason must not leak")
    }

    @Test
    fun `a user store the database cannot serve gets a 503 without a challenge`() {
        val request = MockHttpServletRequest("GET", "/api/v1/accounts").apply {
            setAttribute(REQUEST_ID_ATTRIBUTE, "req-503")
        }
        val response = MockHttpServletResponse()
        // The chain DaoAuthenticationProvider builds when JdbcUserDetailsManager gets no connection.
        val storeFailure = CannotGetJdbcConnectionException(
            "Failed to obtain JDBC Connection",
            SQLTransientConnectionException("HikariPool-1 - Connection is not available, secret reason"),
        )

        ProblemDetailAuthenticationEntryPoint(objectMapper)
            .commence(request, response, InternalAuthenticationServiceException(storeFailure.message, storeFailure))

        assertEquals(503, response.status)
        assertNull(response.getHeader(HttpHeaders.WWW_AUTHENTICATE), "the credentials were never checked")
        val body = objectMapper.readTree(response.contentAsString)
        assertEquals("https://asset-sync-service/errors/database-unavailable", body["type"].asText())
        assertEquals("Database operation failed.", body["detail"].asText())
        assertEquals("req-503", body["requestId"].asText())
        assertFalse(response.contentAsString.contains("secret reason"), "the failure reason must not leak")
    }

    @Test
    fun `any other user store failure gets a 500 without a challenge`() {
        val response = MockHttpServletResponse()

        ProblemDetailAuthenticationEntryPoint(objectMapper).commence(
            MockHttpServletRequest("GET", "/api/v1/accounts"),
            response,
            InternalAuthenticationServiceException("UserDetailsService returned null, which is an interface contract violation"),
        )

        assertEquals(500, response.status)
        assertNull(response.getHeader(HttpHeaders.WWW_AUTHENTICATE))
        val body = objectMapper.readTree(response.contentAsString)
        assertEquals("https://asset-sync-service/errors/internal-error", body["type"].asText())
        assertFalse(response.contentAsString.contains("interface contract"), "the failure reason must not leak")
    }

    @Test
    fun `access denied handler writes a 403 problem detail without a challenge`() {
        val request = MockHttpServletRequest("POST", "/api/v1/addresses/42/sync")
        val response = MockHttpServletResponse()

        ProblemDetailAccessDeniedHandler(objectMapper)
            .handle(request, response, AccessDeniedException("denied"))

        assertEquals(403, response.status)
        assertNull(response.getHeader(HttpHeaders.WWW_AUTHENTICATE))
        val body = objectMapper.readTree(response.contentAsString)
        assertEquals("https://asset-sync-service/errors/forbidden", body["type"].asText())
        assertEquals(403, body["status"].asInt())
        assertEquals("/api/v1/addresses/42/sync", body["instance"].asText())
        assertNull(body["requestId"], "no request id attribute means no requestId property")
    }
}
