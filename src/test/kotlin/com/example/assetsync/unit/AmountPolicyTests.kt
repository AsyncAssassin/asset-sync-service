package com.example.assetsync.unit

import com.example.assetsync.domain.policy.AmountPolicy
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AmountPolicyTests {

    @Test
    fun `amounts that fit numeric 38 18 come back at scale 18`() {
        assertNormalized("12.34", "12.340000000000000000")
        assertNormalized("99999999999999999999.123456789012345678", "99999999999999999999.123456789012345678")
        assertNormalized("1e-18", "0.000000000000000001")
        // Exponent notation stays accepted; only the digit counts of the value matter.
        assertNormalized("1e2", "100.000000000000000000")
        assertNormalized("1E+19", "10000000000000000000.000000000000000000")
        // Zeros beyond the 18th fraction digit carry no value and are dropped exactly.
        assertNormalized("1.0000000000000000000", "1.000000000000000000")
    }

    @Test
    fun `zero fits whatever its exponent`() {
        assertNormalized("0", "0.000000000000000000")
        assertNormalized("0e2147483647", "0.000000000000000000")
        assertNormalized("0.0000000000000000000000", "0.000000000000000000")
    }

    @Test
    fun `amounts beyond 20 integer or 18 fraction digits are rejected`() {
        assertRejected("100000000000000000000")
        assertRejected("1E+20")
        assertRejected("0.0000000000000000001")
        assertRejected("1.5e-18")
    }

    @Test
    fun `extreme exponents are rejected instead of overflowing into a fitting value`() {
        // precision - scale overflowed Int for these, so the old check counted zero integer digits.
        assertRejected("1e2147483647")
        assertRejected("1e2147483648")
        assertRejected("123456789e2147483640")
        assertRejected("10e2147483648")
        assertRejected("1e-2147483647")
    }

    @Test
    fun `negative amounts are rejected`() {
        assertRejected("-1")
        assertRejected("-0.000000000000000001")
    }

    private fun assertNormalized(input: String, expected: String) {
        val normalized = AmountPolicy.normalizedOrNull(BigDecimal(input))
        assertEquals(expected, normalized?.toPlainString(), "normalized form of $input")
        assertEquals(AmountPolicy.SCALE, normalized?.scale(), "scale of $input")
    }

    private fun assertRejected(input: String) {
        assertNull(AmountPolicy.normalizedOrNull(BigDecimal(input)), "$input must be rejected")
    }
}
