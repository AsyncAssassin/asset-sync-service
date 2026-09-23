package com.example.assetsync.domain.policy

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The storage contract of an observed amount, `numeric(38, 18)`: non-negative, at most 20 integer
 * and 18 fraction digits. REST ingestion and provider pages both go through this rule, so an
 * amount that PostgreSQL would silently round or reject never reaches the database.
 */
object AmountPolicy {
    const val MAX_INTEGER_DIGITS = 20
    const val SCALE = 18

    private val ZERO: BigDecimal = BigDecimal.ZERO.setScale(SCALE)

    /**
     * Returns [amount] at scale 18 when it fits, otherwise null. Exponent notation is accepted.
     * Digit counts are computed in Long and without stripTrailingZeros: `precision - scale`
     * overflows Int for exponents near Int.MAX_VALUE (`1e2147483647` would pass as zero integer
     * digits), and stripping zeros can overflow the scale itself.
     */
    fun normalizedOrNull(amount: BigDecimal): BigDecimal? {
        if (amount.signum() < 0) {
            return null
        }
        if (amount.signum() == 0) {
            return ZERO
        }
        val precision = amount.precision().toLong()
        val scale = amount.scale().toLong()
        // A non-zero value has at most precision - 1 trailing zeros to drop, so a scale beyond
        // SCALE + precision - 1 cannot come back to 18 fraction digits without losing digits.
        if (precision - scale > MAX_INTEGER_DIGITS || scale - SCALE >= precision) {
            return null
        }
        return try {
            amount.setScale(SCALE, RoundingMode.UNNECESSARY)
        } catch (exception: ArithmeticException) {
            null
        }
    }
}
