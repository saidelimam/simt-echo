package app.simt.echo.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Plan W10-03 gate: three pooled buffers, zero hot-path growth. */
class LumaBufferPoolTest {

    @Test
    fun threeSlotsThenExhausted() {
        val pool = LumaBufferPool(bufferSize = 16)
        assertEquals(3, pool.freeCount())
        val first = assertNotNull(pool.acquire())
        val second = assertNotNull(pool.acquire())
        val third = assertNotNull(pool.acquire())
        assertEquals(0, pool.freeCount())
        // Fourth frame while three are out: dropped, never grown.
        assertNull(pool.acquire())
        // Distinct slots, analyser writes never alias.
        assertTrue(first !== second && second !== third && first !== third)
    }

    @Test
    fun releaseRecyclesTheSameInstance() {
        val pool = LumaBufferPool(bufferSize = 16)
        val first = assertNotNull(pool.acquire())
        assertNotNull(pool.acquire())
        assertNotNull(pool.acquire())
        assertNull(pool.acquire())
        pool.release(first)
        assertEquals(1, pool.freeCount())
        // Zero allocation in the hot path: the recycled array itself.
        assertSame(first, pool.acquire())
    }

    @Test
    fun unknownArraysAreIgnored() {
        val pool = LumaBufferPool(bufferSize = 16)
        pool.release(ByteArray(16))
        assertEquals(3, pool.freeCount())
    }

    @Test
    fun buffersHaveTheRequestedSize() {
        val pool = LumaBufferPool(bufferSize = 1_024)
        assertEquals(1_024, assertNotNull(pool.acquire()).size)
    }

    @Test
    fun withBufferReturnsTheSlotWhenTheBlockCompletes() {
        val pool = LumaBufferPool(bufferSize = 16)
        var ran = false
        assertTrue(pool.withBuffer { ran = true })
        assertTrue(ran)
        assertEquals(3, pool.freeCount())
    }

    @Test
    fun withBufferReturnsTheSlotWhenTheBlockThrows() {
        val pool = LumaBufferPool(bufferSize = 16)
        repeat(10) {
            assertFailsWith<IllegalStateException> { pool.withBuffer { error("consumer failed") } }
        }
        // Ten failing consumers later nothing leaked: the old DROP_LATEST
        // flow stranded a buffer per dropped frame and starved after three.
        assertEquals(3, pool.freeCount())
    }

    @Test
    fun withBufferSkipsTheBlockWhenEverySlotIsOut() {
        val pool = LumaBufferPool(bufferSize = 16)
        repeat(3) { assertNotNull(pool.acquire()) }
        var ran = false
        assertFalse(pool.withBuffer { ran = true })
        assertFalse(ran)
        assertEquals(0, pool.freeCount())
    }
}
