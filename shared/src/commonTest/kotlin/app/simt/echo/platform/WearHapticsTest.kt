package app.simt.echo.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WearHapticsTest {

    @Test
    fun codecRoundTripsEveryPattern() {
        for (pattern in HapticPattern.entries) {
            assertEquals(pattern, WearHapticCodec.decode(WearHapticCodec.encode(pattern)))
        }
    }

    @Test
    fun codecRejectsUnknownPayload() {
        assertNull(WearHapticCodec.decode("SPIN".encodeToByteArray()))
        assertNull(WearHapticCodec.decode(byteArrayOf()))
    }

    @Test
    fun pathsAreEchoScoped() {
        assertTrue(EchoWearPaths.MESSAGE_PATH_HAPTICS.startsWith("/echo/"))
        assertTrue(EchoWearPaths.CAPABILITY_WEAR_APP.startsWith("echo_"))
    }

    @Test
    fun mirroringPlaysLocallyAndForwards() {
        val played = mutableListOf<HapticPattern>()
        val mirrored = mutableListOf<HapticPattern>()
        val local = object : Haptics {
            override fun play(pattern: HapticPattern) {
                played += pattern
            }
        }
        val haptics = WearMirroringHaptics(local) { mirrored += it }
        haptics.play(HapticPattern.LEFT)
        haptics.play(HapticPattern.ARRIVE)
        assertEquals(listOf(HapticPattern.LEFT, HapticPattern.ARRIVE), played)
        assertEquals(listOf(HapticPattern.LEFT, HapticPattern.ARRIVE), mirrored)
    }

    @Test
    fun mirroringSurvivesMirrorFailure() {
        var played = false
        val local = object : Haptics {
            override fun play(pattern: HapticPattern) {
                played = true
            }
        }
        val haptics = WearMirroringHaptics(local) { throw IllegalStateException("no radio") }
        haptics.play(HapticPattern.TICK)
        assertTrue(played)
    }

    @Test
    fun mirrorReceivesSameCues() {
        val mirrored = mutableListOf<HapticPattern>()
        val local = object : Haptics {
            override fun play(pattern: HapticPattern) = Unit
        }
        val haptics = WearMirroringHaptics(local) { mirrored += it }
        for (pattern in HapticPattern.entries) haptics.play(pattern)
        assertEquals(HapticPattern.entries.toList(), mirrored)
    }
}
