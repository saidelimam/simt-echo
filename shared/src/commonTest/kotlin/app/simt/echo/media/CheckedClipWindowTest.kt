package app.simt.echo.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** W10-02 G5/W11-03: exporters take a caller-clamped window and refuse an empty one. */
class CheckedClipWindowTest {

    @Test
    fun aValidWindowPassesThroughUntouched() {
        assertEquals(ClipWindow(1_000L, 4_000L), checkedClipWindow(1_000L, 4_000L))
    }

    @Test
    fun aNegativeStartClampsToZero() {
        assertEquals(ClipWindow(0L, 3_000L), checkedClipWindow(-250L, 3_000L))
    }

    @Test
    fun aWindowWithNothingInItFailsWithATypedError() {
        val empty = assertFailsWith<EmptyClipWindowException> { checkedClipWindow(5_000L, 5_000L) }
        assertEquals(5_000L, empty.startMs)
        assertEquals(5_000L, empty.endMs)
    }

    @Test
    fun anInvertedWindowFailsWithATypedError() {
        // Start past a clamped end (a capture window beyond a short video).
        assertFailsWith<EmptyClipWindowException> { checkedClipWindow(9_000L, 6_000L) }
    }

    @Test
    fun aNegativeStartDoesNotRescueAnEndAtOrBeforeZero() {
        assertFailsWith<EmptyClipWindowException> { checkedClipWindow(-500L, 0L) }
    }

    @Test
    fun theCallersClampedFallbackWindowFeedsStraightIn() {
        // stripFallbackWindow never inverts, so a short video yields an
        // empty window (start == end), not a Transformer error.
        val window = stripFallbackWindow(tMs = 30_000L, durationMs = 5_000L)
        assertFailsWith<EmptyClipWindowException> { checkedClipWindow(window.startMs, window.endMs) }
    }
}
