package com.example.assetsync.config

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.exc.StreamConstraintsException
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.Module
import com.fasterxml.jackson.databind.deser.std.NumberDeserializers
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.type.LogicalType
import java.math.BigDecimal
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

    /**
     * A number with a fraction where an integer is expected fails the read instead of losing the
     * fraction, which Jackson does by default: an `eventIndex` of 1.9 would be stored as 1. A whole
     * number written as a float, such as `1.0` or `1e3`, is read as that integer, for `Int` and
     * `Long`, the integer types the requests and pages use; any other integer type refuses floats.
     * HTTP bridge pages are read with the same mapper, so there a fraction is bad data of the address.
     */
    @Bean
    fun integersStayIntegers(): Module =
        SimpleModule("integers-stay-integers").apply {
            integral(Int::class.javaPrimitiveType!!) { it.intValueExact() }
            integral(Int::class.javaObjectType) { it.intValueExact() }
            integral(Long::class.javaPrimitiveType!!) { it.longValueExact() }
            integral(Long::class.javaObjectType) { it.longValueExact() }
        }

    @Bean
    fun noFloatAsOtherIntegers(): Jackson2ObjectMapperBuilderCustomizer =
        Jackson2ObjectMapperBuilderCustomizer { builder -> builder.featuresToDisable(DeserializationFeature.ACCEPT_FLOAT_AS_INT) }

    private fun <T : Any> SimpleModule.integral(type: Class<T>, exact: (BigDecimal) -> T) {
        @Suppress("UNCHECKED_CAST")
        val standard = NumberDeserializers.find(type, type.name) as JsonDeserializer<T>
        addDeserializer(type, IntegralNumberDeserializer(type, standard, exact))
    }

    override fun extendMessageConverters(converters: MutableList<HttpMessageConverter<*>>) {
        converters.removeIf { it is MappingJackson2YamlHttpMessageConverter }
    }

    companion object {
        const val MAX_JSON_STRING_LENGTH = 100_000
    }
}

/**
 * Reads a whole number exactly: a float token counts when its value is a whole number within the
 * range of the type, and every other token goes to Jackson's own deserializer for the type.
 */
private class IntegralNumberDeserializer<T : Any>(
    type: Class<T>,
    private val standard: JsonDeserializer<T>,
    private val exact: (BigDecimal) -> T,
) : StdDeserializer<T>(type) {

    override fun deserialize(parser: JsonParser, context: DeserializationContext): T {
        if (parser.currentToken != JsonToken.VALUE_NUMBER_FLOAT) {
            return standard.deserialize(parser, context)
        }
        val value = parser.decimalValue
        return try {
            exact(value)
        } catch (exception: ArithmeticException) {
            @Suppress("UNCHECKED_CAST")
            context.handleWeirdNumberValue(handledType(), value, "not a whole number within range") as T
        }
    }

    override fun getNullValue(context: DeserializationContext): T? = standard.getNullValue(context)

    override fun logicalType(): LogicalType = LogicalType.Integer
}

/** Whether a JSON read failed on a parser limit, such as the string cap, rather than on syntax. */
fun Throwable.exceedsJsonReadLimit(): Boolean =
    generateSequence(this) { it.cause }.any { it is StreamConstraintsException }
