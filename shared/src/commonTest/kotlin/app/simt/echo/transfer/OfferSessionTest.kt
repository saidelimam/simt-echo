package app.simt.echo.transfer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * W20-03 gate: one owner, one generation, one served offer.
 *
 * These cases are why a second invitation source is safe: a replaced or
 * stopped offer can never publish, and an offer that expires stops being
 * served without a coroutine having to notice.
 */
class OfferSessionTest {

    private fun session(
        now: () -> Long = { 0L },
        lifetimeMs: Long = OFFER_LIFETIME_MS,
        onStop: () -> Unit = {},
    ) = OfferSession(nowMs = now, lifetimeMs = lifetimeMs, stopServing = onStop)

    @Test
    fun theSecondOfTwoConcurrentReOffersIsTheOneLeftServed() = runTest {
        var stops = 0
        val session = session(onStop = { stops++ })
        val gate = CompletableDeferred<Unit>()

        // First offer starts, then blocks inside preparation — exactly the
        // shape of a slow bundle encode overtaken by a settings change.
        val first = launch {
            session.replace { generation ->
                gate.await()
                session.stamp(generation, "uri-A", "192.168.1.10", 1)
            }
        }
        runCurrent()
        val second = launch {
            session.replace { generation ->
                session.stamp(generation, "uri-B", "192.168.1.11", 2)
            }
        }
        runCurrent()
        gate.complete(Unit)
        first.join()
        second.join()

        // The newest selection alone is served: the stale one stopped
        // whatever it had started and never published.
        assertEquals("uri-B", session.current?.qrUri)
        assertEquals(2L, session.current?.generation)
        assertEquals(2, session.current?.port)
        assertTrue(stops >= 1, "the replaced offer must stop what it started")
    }

    @Test
    fun aStaleGenerationCannotClearTheCurrentOffer() = runTest {
        val session = session()
        val first = session.replace { generation -> session.stamp(generation, "uri-1", "h", 1) }
        assertNotNull(first)
        val second = session.replace { generation -> session.stamp(generation, "uri-2", "h", 2) }
        assertNotNull(second)

        assertFalse(session.isCurrent(first.generation))
        assertTrue(session.isCurrent(second.generation))
        assertEquals("uri-2", session.current?.qrUri)
    }

    @Test
    fun invalidateIsImmediateAndBurnsTheGeneration() = runTest {
        val session = session()
        val offer = session.replace { generation -> session.stamp(generation, "uri", "h", 1) }
        assertNotNull(offer)

        // Synchronous: the screen must not keep showing a QR while a
        // coroutine gets around to stopping it.
        val burned = session.invalidate()
        assertNull(session.current)
        assertFalse(session.isCurrent(offer.generation))
        assertEquals(burned, session.currentGeneration)
    }

    @Test
    fun anOfferStopsAtItsTenMinuteWindow() = runTest {
        var now = 1_000L
        var stops = 0
        val session = session(now = { now }, onStop = { stops++ })
        val offer = session.replace { generation -> session.stamp(generation, "uri", "h", 1) }
        assertNotNull(offer)
        assertEquals(1_000L + OFFER_LIFETIME_MS, offer.expiresAtMs)

        now = 1_000L + OFFER_LIFETIME_MS - 1
        assertNull(session.expireIfNeeded(), "one millisecond short is still served")
        assertNotNull(session.current)

        now = 1_000L + OFFER_LIFETIME_MS
        assertEquals(offer.generation, session.expireIfNeeded())
        assertNull(session.current)
        assertEquals(1, stops)
        // Expiring an expired session again does nothing.
        assertNull(session.expireIfNeeded())
        assertEquals(1, stops)
    }

    @Test
    fun stoppingIsIdempotentAndCheapWhenNothingIsServed() = runTest {
        var stops = 0
        val session = session(onStop = { stops++ })
        session.stop()
        session.stop()
        assertEquals(0, stops, "nothing was serving, so nothing had to stop")

        session.replace { generation -> session.stamp(generation, "uri", "h", 1) }
        session.stop()
        session.stop()
        assertEquals(1, stops, "one live offer, one stop")
        assertNull(session.current)
    }

    @Test
    fun aFailedPreparationLeavesNothingServingAndKeepsTheFailure() = runTest {
        var stops = 0
        val session = session(onStop = { stops++ })
        val failure = assertNotNull(
            runCatching {
                session.replace { error("no Wi-Fi") }
            }.exceptionOrNull(),
        )
        assertEquals("no Wi-Fi", failure.message)
        assertNull(session.current)
        assertEquals(1, stops, "a partially started server is closed")
    }

    @Test
    fun anInvitationIsNeverFormattedIntoADiagnostic() = runTest {
        val session = session()
        val offer = session.replace {
            it.let { generation -> session.stamp(generation, "simt-echo://r?v=1&t=secret&k=secret", "h", 1) }
        }
        val text = assertNotNull(offer).toString()
        assertFalse(text.contains("secret"), text)
        assertTrue(text.contains("redacted"), text)
    }
}
