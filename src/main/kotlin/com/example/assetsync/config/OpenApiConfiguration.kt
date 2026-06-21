package com.example.assetsync.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfiguration {

    @Bean
    fun assetSyncOpenApi(): OpenAPI =
        OpenAPI()
            .info(
                Info()
                    .title("asset-sync-service API")
                    .version("v1")
                    .description("Backend service for synchronizing observable public asset transaction state."),
            )
}
