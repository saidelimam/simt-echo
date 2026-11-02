package app.simt.echo.capture

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Plan W10-03 gate: the rate governor throttles deterministically. */
class FrameRateGovernorTest {

    @Test
    fun recordGraphAdmitsFirstFrameThenHolds8Hz() {
        val governor = FrameRateGovernor(FrameRateGovernor.RECORD_INTERVAL_NS)
        assertTrue(governor.shouldEmit(0L))
        // 124 ms later: still inside the 125 ms window.
        assertFalse(governor.shouldEmit(124_000_000L))
        // At exactly the interval: admitted again.
        assertTrue(governor.shouldEmit(125_000_000L))
    }

    @Test
    fun navigateGraphHolds2Hz() {
        val governor = FrameRateGovernor(FrameRateGovernor.NAVIGATE_INTERVAL_NS)
        assertTrue(governor.shouldEmit(1_000L))
        assertFalse(governor.shouldEmit(1_000L + 499_000_000L))
        assertTrue(governor.shouldEmit(1_000L + 500_000_000L))
    }

    @Test
    fun moderateThermalHalvesTheRate() {
        val governor = FrameRateGovernor(FrameRateGovernor.RECORD_INTERVAL_NS)
        assertTrue(governor.shouldEmit(0L, ThermalState.MODERATE))
        // Base interval alone is no longer enough: doubled to 250 ms.
        assertFalse(governor.shouldEmit(125_000_000L, ThermalState.MODERATE))
        assertTrue(governor.shouldEmit(250_000_000L, ThermalState.MODERATE))
    }

    @Test
    fun severeThermalStopsAnalysis() {
        val governor = FrameRateGovernor(FrameRateGovernor.RECORD_INTERVAL_NS)
        assertFalse(governor.shouldEmit(0L, ThermalState.SEVERE))
        assertFalse(governor.shouldEmit(Long.MAX_VALUE / 2, ThermalState.SEVERE))
        // Cooling back down resumes without a reset.
        assertTrue(governor.shouldEmit(Long.MAX_VALUE / 2, ThermalState.NORMAL))
    }

    @Test
    fun coolingRestoresTheBaseRate() {
        val governor = FrameRateGovernor(FrameRateGovernor.RECORD_INTERVAL_NS)
        assertTrue(governor.shouldEmit(0L, ThermalState.MODERATE))
        // A cool frame before the base interval elapsed is still held back:
        // the governor never emits faster than base rate.
        assertFalse(governor.shouldEmit(124_000_000L, ThermalState.NORMAL))
        assertTrue(governor.shouldEmit(125_000_000L, ThermalState.NORMAL))
    }

    @Test
    fun lateFramesAfterIdleAreAdmitted() {
        val governor = FrameRateGovernor(FrameRateGovernor.RECORD_INTERVAL_NS)
        assertTrue(governor.shouldEmit(100L))
        assertTrue(governor.shouldEmit(100L + 10_000_000_000L))
    }
}
