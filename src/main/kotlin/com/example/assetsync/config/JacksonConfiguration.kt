package com.example.assetsync.config

import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Caps every string the application's `ObjectMapper` reads, in request bodies and HTTP bridge
 * pages alike, at [MAX_JSON_STRING_LENGTH] characters instead of Jackson's default of 20 million.
 * The longest legitimate string is a provider cursor (`max-cursor-length`, 4096 by default). A
 * longer string fails the parse, which the API answers with `400 invalid-request`.
 */
@Configuration
class JacksonConfiguration {

    @Bean
    fun jsonStringLengthLimit(): Jackson2ObjectMapperBuilderCustomizer =
        Jackson2ObjectMapperBuilderCustomizer { builder ->
            builder.postConfigurer { objectMapper ->
                val factory = objectMapper.factory
                factory.setStreamReadConstraints(
                    factory.streamReadConstraints().rebuild().maxStringLength(MAX_JSON_STRING_LENGTH).build(),
                )
            }
        }

    companion object {
        const val MAX_JSON_STRING_LENGTH = 100_000
    }
}
