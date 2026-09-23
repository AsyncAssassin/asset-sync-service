package com.example.assetsync.config

import com.fasterxml.jackson.core.exc.StreamConstraintsException
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.converter.yaml.MappingJackson2YamlHttpMessageConverter
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Bounds what reading JSON can cost. The application's `ObjectMapper`, used for request bodies and
 * HTTP bridge pages alike, accepts strings of at most [MAX_JSON_STRING_LENGTH] characters instead
 * of Jackson's default of 20 million. Legitimate strings are far shorter: request fields stop at
 * 255 characters, a provider cursor at 4096, and checkpoint metadata at 16 384 bytes in total. The
 * DTOs ignore unknown properties outright, so an unknown string is skipped whatever its length.
 *
 * The API speaks JSON only. Spring MVC would also read `application/yaml` bodies, because springdoc
 * brings jackson-dataformat-yaml, with a mapper that has none of these limits, so that converter
 * is removed and such a request gets `415`.
 */
@Configuration
class JacksonConfiguration : WebMvcConfigurer {

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

    override fun extendMessageConverters(converters: MutableList<HttpMessageConverter<*>>) {
        converters.removeIf { it is MappingJackson2YamlHttpMessageConverter }
    }

    companion object {
        const val MAX_JSON_STRING_LENGTH = 100_000
    }
}

/** Whether a JSON read failed on a parser limit, such as the string cap, rather than on syntax. */
fun Throwable.exceedsJsonReadLimit(): Boolean =
    generateSequence(this) { it.cause }.any { it is StreamConstraintsException }
