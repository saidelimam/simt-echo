package app.simt.echo.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** W13-12 gate: the `/echo/nav` codec mirrors shared `WearNavSync`. */
class EchoNavCodecTest {

    @Test
    fun roundTripsASnapshot() {
        val original = EchoNavState(arrowDeg = 90, distanceM = 12, cue = "Turn left", phase = "WALK")
        assertEquals(original, EchoNavCodec.decode(EchoNavCodec.encode(original)))
    }

    @Test
    fun roundTripsEscapesAndUnicode() {
        val original = EchoNavState(
            arrowDeg = -135,
            distanceM = 0,
            cue = "Turn; left\\now\nat the café",
            phase = "ARRIVED",
        )
        assertEquals(original, EchoNavCodec.decode(EchoNavCodec.encode(original)))
    }

    @Test
    fun decodesTheDocumentedWireFormat() {
        val decoded = EchoNavCodec.decode("v1\n90\n12\nWALK\nTurn left".toByteArray(Charsets.UTF_8))
        assertEquals(EchoNavState(90, 12, "Turn left", "WALK"), decoded)
    }

    @Test
    fun rejectsNonV1Payloads() {
        assertNull(EchoNavCodec.decode(ByteArray(0)))
        assertNull(EchoNavCodec.decode("hello".toByteArray(Charsets.UTF_8)))
        assertNull(EchoNavCodec.decode("v2\n90\n12\nWALK\nTurn left".toByteArray(Charsets.UTF_8)))
        assertNull(EchoNavCodec.decode("v1\n90\n12\nWALK".toByteArray(Charsets.UTF_8)))
        assertNull(EchoNavCodec.decode("v1\n90\n12\nWALK\ncue\nextra".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun rejectsOutOfRangeNumerics() {
        assertNull(EchoNavCodec.decode("v1\n181\n12\nWALK\ncue".toByteArray(Charsets.UTF_8)))
        assertNull(EchoNavCodec.decode("v1\n-181\n12\nWALK\ncue".toByteArray(Charsets.UTF_8)))
        assertNull(EchoNavCodec.decode("v1\nfar\n12\nWALK\ncue".toByteArray(Charsets.UTF_8)))
        assertNull(EchoNavCodec.decode("v1\n90\n-1\nWALK\ncue".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun rejectsEmptyPhaseAndBadEscapes() {
        assertNull(EchoNavCodec.decode("v1\n90\n12\n\ncue".toByteArray(Charsets.UTF_8)))
        assertNull(EchoNavCodec.decode("v1\n90\n12\nWALK\nbad\\escape".toByteArray(Charsets.UTF_8)))
        assertNull(EchoNavCodec.decode("v1\n90\n12\nWALK\ntrailing\\".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun keepsUnknownPhaseVerbatim() {
        val decoded = EchoNavCodec.decode("v1\n0\n5\nRELOCKING_V2\nFinding you…".toByteArray(Charsets.UTF_8))
        assertEquals(EchoNavState(0, 5, "Finding you…", "RELOCKING_V2"), decoded)
    }
}
