package app.simt.echo.ui.motion

import kotlin.test.Test
import kotlin.test.assertEquals

/** W13-11 listReveal: records arrive in order, and long lists never wait. */
class RevealTest {

    @Test
    fun recordsStaggerInOrder() {
        assertEquals(0L, revealDelayMs(0))
        assertEquals(REVEAL_STAGGER_MS, revealDelayMs(1))
        assertEquals(3 * REVEAL_STAGGER_MS, revealDelayMs(3))
    }

    @Test
    fun theStaggerIsCappedForLongLists() {
        val cap = REVEAL_MAX_STAGGERED * REVEAL_STAGGER_MS
        assertEquals(cap, revealDelayMs(REVEAL_MAX_STAGGERED))
        assertEquals(cap, revealDelayMs(500))
        assertEquals(0L, revealDelayMs(-3))
    }
}
