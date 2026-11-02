package app.simt.echo.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

class ReadoutsTest {
    @Test
    fun oneDecimalTruncatesSoTheReadoutNeverOvershoots() {
        assertEquals("23.4", oneDecimal(23.49))
        assertEquals("0.0", oneDecimal(0.0))
    }

    @Test
    fun oneDecimalClampsNegativesInsteadOfRenderingTwoSigns() {
        assertEquals("0.0", oneDecimal(-0.5))
        assertEquals("0.0", oneDecimal(-1.25))
    }

    @Test
    fun roundedOneDecimalRoundsAFloatWidenedToDouble() {
        // 1.3f widens to 1.2999999523…; truncation would read 1.2.
        assertEquals("1.3", roundedOneDecimal(1.3f.toDouble()))
        assertEquals("2.0", roundedOneDecimal(1.96))
    }

    @Test
    fun roundedOneDecimalIsSignSafe() {
        assertEquals("-0.5", roundedOneDecimal(-0.5))
        assertEquals("-1.3", roundedOneDecimal(-1.25))
    }
}
