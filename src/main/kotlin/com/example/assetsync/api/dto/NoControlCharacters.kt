package com.example.assetsync.api.dto

import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass

/**
 * The value holds no control character, U+0000 to U+001F and U+007F to U+009F, as
 * [Character.isISOControl] counts them and the chain identity rules refuse them; [allowLineBreaks]
 * keeps tabs and line breaks for free text such as a label. The value is checked as the service
 * stores it, trimmed, so surrounding whitespace stays accepted. PostgreSQL cannot store U+0000 in
 * text at all, so without this rule such a value failed only at the database.
 *
 * A validator of its own rather than a `@Pattern`: the published OpenAPI document would carry the
 * pattern, and a character class such as `\p{Cntrl}` is Java-only.
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [NoControlCharactersValidator::class])
annotation class NoControlCharacters(
    val message: String,
    val allowLineBreaks: Boolean = false,
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

class NoControlCharactersValidator : ConstraintValidator<NoControlCharacters, String?> {
    private var allowLineBreaks = false

    override fun initialize(annotation: NoControlCharacters) {
        allowLineBreaks = annotation.allowLineBreaks
    }

    override fun isValid(value: String?, context: ConstraintValidatorContext): Boolean =
        value == null || value.trim().none { it.isISOControl() && !(allowLineBreaks && (it == '\t' || it == '\n' || it == '\r')) }
}
