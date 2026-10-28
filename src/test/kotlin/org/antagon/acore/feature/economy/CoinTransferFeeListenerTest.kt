package org.antagon.acore.feature.economy

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoinTransferFeeListenerTest {

    private fun isValidTransferAmount(amountStr: String): Boolean {
        val amount = amountStr.toDoubleOrNull()
        return amount != null && amount.isFinite() && amount > 0.0
    }

    @Test
    fun testValidAmounts() {
        assertTrue(isValidTransferAmount("100"))
        assertTrue(isValidTransferAmount("100.5"))
        assertTrue(isValidTransferAmount("0.01"))
    }

    @Test
    fun testInvalidAmounts() {
        assertFalse(isValidTransferAmount("0"))
        assertFalse(isValidTransferAmount("0.0"))
        assertFalse(isValidTransferAmount("-100"))
        assertFalse(isValidTransferAmount("abc"))
    }

    @Test
    fun testNanAndInfinityExploits() {
        assertFalse(isValidTransferAmount("NaN"))
        assertFalse(isValidTransferAmount("nan"))
        assertFalse(isValidTransferAmount("Infinity"))
        assertFalse(isValidTransferAmount("-Infinity"))
        assertFalse(isValidTransferAmount("infinity"))
        assertFalse(isValidTransferAmount("+Infinity"))
    }
}
