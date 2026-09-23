package com.example.assetsync.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

private const val BASIC_AUTH_SCHEME = "basicAuth"

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
}
