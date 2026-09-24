package com.example.assetsync.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springdoc.core.customizers.GlobalOpenApiCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE

private const val BASIC_AUTH_SCHEME = "basicAuth"
private const val PROBLEM_DETAIL_SCHEMA = "ProblemDetail"

/**
 * An error every API operation shares, declared once under `components.responses`. [mutatingOnly]
 * marks the 403: `READ` may call the methods in [API_READ_METHODS], so only the operations that
 * change state need `OPERATOR`. The operation-specific errors (404, 409, 415, 429) are in
 * docs/api.md section 14.
 */
private class SharedProblem(val status: String, val name: String, val description: String, val mutatingOnly: Boolean = false)

private val SHARED_PROBLEMS = listOf(
    SharedProblem("400", "BadRequest", "The request is invalid: malformed JSON, a field or parameter that breaks its rules, or failed validation, listed in `errors`."),
    SharedProblem("401", "Unauthorized", "HTTP Basic credentials are missing or wrong. Not in the local and test profiles, which check no credentials."),
    SharedProblem(
        "403",
        "Forbidden",
        "The caller's role may not call this operation; it needs OPERATOR. Not in the local and test profiles, which check no credentials.",
        mutatingOnly = true,
    ),
    SharedProblem("500", "InternalError", "An unexpected failure; the detail is generic and the exception goes to the log."),
    SharedProblem("503", "DatabaseUnavailable", "The database could not serve the request, the credential check included."),
)

@Configuration
class OpenApiConfiguration {

    /**
     * Declares HTTP Basic as the global security requirement, so Swagger UI offers Authorize and
     * sends the entered credentials, which is how a caller switches between the READ and OPERATOR
     * users. `local` and `test` check no credentials and ignore any that are sent.
     */
    @Bean
    fun assetSyncOpenApi(): OpenAPI =
        OpenAPI()
            .info(
                Info()
                    .title("asset-sync-service API")
                    .version("v1")
                    .description("Backend service for synchronizing observable public asset transaction state."),
            )
            .components(
                Components().addSecuritySchemes(
                    BASIC_AUTH_SCHEME,
                    SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("basic")
                        .description("Database-backed users; required in every profile except local and test."),
                ),
            )
            .addSecurityItem(SecurityRequirement().addList(BASIC_AUTH_SCHEME))

    /**
     * Declares the [SHARED_PROBLEMS] once as `application/problem+json` responses with the
     * ProblemDetail schema, and refers every operation to them; the success codes come from each
     * controller. `local` and `test` check no credentials, so 401 and 403 do not occur there. A
     * global customizer, so a grouped document gets them too.
     */
    @Bean
    fun sharedProblemResponses(): GlobalOpenApiCustomizer =
        GlobalOpenApiCustomizer { openApi ->
            val components = openApi.components
            components.addSchemas(PROBLEM_DETAIL_SCHEMA, problemDetailSchema())
            val content = Content().addMediaType(
                APPLICATION_PROBLEM_JSON_VALUE,
                MediaType().schema(Schema<Any>().`$ref`(PROBLEM_DETAIL_SCHEMA)),
            )
            SHARED_PROBLEMS.forEach { components.addResponses(it.name, ApiResponse().description(it.description).content(content)) }
            openApi.paths.orEmpty().values.forEach { path ->
                path.readOperationsMap().forEach { (method, operation) ->
                    SHARED_PROBLEMS
                        .filter { !it.mutatingOnly || API_READ_METHODS.none { read -> read.name() == method.name } }
                        .forEach { operation.responses.putIfAbsent(it.status, ApiResponse().`$ref`(it.name)) }
                }
            }
        }

    private fun problemDetailSchema(): Schema<Any> =
        ObjectSchema()
            .description("RFC 9457 problem detail. Some errors add domain properties, such as chainId.")
            .addProperty("type", StringSchema().format("uri"))
            .addProperty("title", StringSchema())
            .addProperty("status", IntegerSchema())
            .addProperty("detail", StringSchema())
            .addProperty("instance", StringSchema().format("uri-reference").description("The request path."))
            .addProperty("requestId", StringSchema())
            .addProperty("errors", ArraySchema().items(StringSchema()).description("Failed validations, as `field: message`."))
            .additionalProperties(true)
}
