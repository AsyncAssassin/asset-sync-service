package com.example.assetsync.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

private const val BASIC_AUTH_SCHEME = "basicAuth"
private const val PROBLEM_DETAIL_SCHEMA = "ProblemDetail"

/** The errors any API operation can answer with; the operation-specific ones are in docs/api.md. */
private val COMMON_PROBLEMS = linkedMapOf(
    "400" to "The request is invalid: malformed JSON, a field or parameter that breaks its rules, or failed validation.",
    "401" to "HTTP Basic credentials are missing or wrong, in every profile except local and test.",
    "403" to "The caller's role may not call this operation, in every profile except local and test.",
    "503" to "The database could not serve the request.",
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
     * Adds the errors in [COMMON_PROBLEMS] to every operation as `application/problem+json`, and
     * the ProblemDetail schema they share. The success codes come from each controller; the
     * operation-specific errors (404, 409, 429, ...) stay in `docs/api.md` section 14 instead of
     * being repeated here.
     */
    @Bean
    fun commonProblemResponses(): OpenApiCustomizer =
        OpenApiCustomizer { openApi ->
            openApi.components.addSchemas(PROBLEM_DETAIL_SCHEMA, problemDetailSchema())
            val content = Content().addMediaType(
                org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                MediaType().schema(Schema<Any>().`$ref`(PROBLEM_DETAIL_SCHEMA)),
            )
            openApi.paths.orEmpty().values.flatMap { it.readOperations() }.forEach { operation ->
                COMMON_PROBLEMS.forEach { (status, description) ->
                    operation.responses.putIfAbsent(status, ApiResponse().description(description).content(content))
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
            .addProperty("instance", StringSchema().format("uri"))
            .addProperty("requestId", StringSchema())
            .additionalProperties(true)
}
