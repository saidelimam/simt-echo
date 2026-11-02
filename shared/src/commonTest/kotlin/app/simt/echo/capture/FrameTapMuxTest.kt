package app.simt.echo.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FrameTapMuxTest {
    private val sample = FrameSample(1L, 8, 8, 1, ByteArray(64))

    @Test
    fun ledgerPrecedesThreeBoundedGuestsAndLegacyDetachPreservesThem() {
        val mux = FrameTapMux()
        val calls = mutableListOf<Int>()
        val guests = List(3) { i -> FrameConsumer { calls += i + 1 } }
        guests.forEach { assertTrue(mux.addConsumer(it)) }
        assertFalse(mux.addConsumer(FrameConsumer {}))
        assertFalse(mux.addConsumer(guests[0]))
        mux.setConsumer(FrameConsumer { calls += 0 })
        mux.onFrame(sample)
        assertEquals(listOf(0, 1, 2, 3), calls)
        calls.clear()
        mux.setConsumer(null)
        mux.onFrame(sample)
        assertEquals(listOf(1, 2, 3), calls)
        guests.forEach { assertTrue(mux.removeConsumer(it)) }
        assertFalse(mux.hasConsumers)
        assertFalse(mux.wantsFrame(2L))
    }

    @Test
    fun registrationChangesInsideCallbackTakeEffectNextFrame() {
        val mux = FrameTapMux()
        val calls = mutableListOf<Int>()
        val old = FrameConsumer { calls += 1 }
        val replacement = FrameConsumer { calls += 2 }
        mux.setConsumer {
            calls += 0
            mux.removeConsumer(old)
            mux.addConsumer(replacement)
        }
        mux.addConsumer(old)
        mux.onFrame(sample)
        assertEquals(listOf(0, 1), calls)
        calls.clear()
        mux.onFrame(sample)
        assertEquals(listOf(0, 2), calls)
    }

    @Test
    fun wantsFrameShortCircuitsAndUninterestedGuestsReceiveNothing() {
        val mux = FrameTapMux()
        var asked = 0
        var received = 0
        mux.setConsumer(object : FrameConsumer {
            override fun wantsFrame(tNs: Long): Boolean { asked++; return tNs == 1L }
            override fun onFrame(sample: FrameSample) { received++ }
        })
        mux.addConsumer(object : FrameConsumer {
            override fun wantsFrame(tNs: Long): Boolean { asked++; return false }
            override fun onFrame(sample: FrameSample) { error("uninterested guest") }
        })
        assertTrue(mux.wantsFrame(1L))
        assertEquals(1, asked)
        assertFalse(mux.wantsFrame(2L))
        assertEquals(3, asked)
        mux.onFrame(sample)
        assertEquals(1, received)
        assertEquals(0L, mux.consumerFailures)
    }

    @Test
    fun oneFailingConsumerCannotStarveOthers() {
        val mux = FrameTapMux()
        var received = 0
        mux.setConsumer { error("consumer failure") }
        mux.addConsumer(FrameConsumer { received++ })
        mux.onFrame(sample)
        assertEquals(1, received)
        assertEquals(1L, mux.consumerFailures)
    }
}