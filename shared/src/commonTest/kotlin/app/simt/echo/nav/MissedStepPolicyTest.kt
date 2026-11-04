package app.simt.echo.nav

import kotlin.test.Test
import kotlin.test.assertEquals

class MissedStepPolicyTest {
    @Test
    fun inflationIsQuadratureAndResetClearsIt() {
        val policy = MissedStepPolicy()
        repeat(4) { policy.onSuspect() }
        assertEquals(2.0 * MissedStepPolicy.INFLATION_M, policy.sigmaBonusM, 1e-12)
        policy.reset()
        assertEquals(0.0, policy.sigmaBonusM)
    }
}
