package app.simt.echo.transfer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** W2-03 gate for the §5.4 QR URI: build/parse, rejection, length. */
class QrPayloadTest {

    private val payload = QrPayload(
        host = "192.168.1.20",
        port = 51234,
        token = ByteArray(16) { it.toByte() },
        key = ByteArray(32) { (it * 3).toByte() },
        sha8Hex = "0123456789abcdef",
        name = "Garage → Lift",
    )

    @Test
    fun buildParseRoundTrips() {
        val parsed = QrPayload.parse(payload.build()).getOrThrow()
        assertEquals(payload, parsed)
        assertNull(parsed.relayCode)
    }

    @Test
    fun relayCodeRoundTrips() {
        val withRelay = payload.copy(relayCode = "X7K2P")
        val parsed = QrPayload.parse(withRelay.build()).getOrThrow()
        assertEquals("X7K2P", parsed.relayCode)
    }

    @Test
    fun builtUriStaysWithinScannerLimit() {
        assertTrue(payload.build().length <= 200, payload.build())
        // Even a max-length name keeps the URI scannable.
        val longName = payload.copy(name = "N".repeat(40))
        assertTrue(longName.build().length <= 200, longName.build())
    }

    @Test
    fun nameIsTruncatedAt40Chars() {
        val parsed = QrPayload.parse(payload.copy(name = "N".repeat(60)).build()).getOrThrow()
        assertEquals(40, parsed.name.length)
    }

    @Test
    fun percentEncodingRoundTripsUnicodeAndSpaces() {
        assertEquals("Garage → Lift", percentDecode(percentEncode("Garage → Lift")))
        assertEquals("a%20b", percentEncode("a b"))
        assertEquals("a b", percentDecode("a%20b"))
    }

    @Test
    fun rejectsForeignUri() {
        assertNotNull(QrPayload.parse("https://example.com/r?v=1").exceptionOrNull())
        assertNotNull(QrPayload.parse("simt-echo://x?v=1").exceptionOrNull())
    }

    @Test
    fun rejectsBadVersionHostPortAndSecrets() {
        val good = payload.build()
        fun broken(mutate: (String) -> String) =
            assertNotNull(QrPayload.parse(mutate(good)).exceptionOrNull(), mutate(good))

        broken { it.replace("v=1", "v=2") }
        broken { it.replace("h=192.168.1.20", "h=999.1.1.1") }
        broken { it.replace("h=192.168.1.20", "h=phone.local") }
        broken { it.replace("p=51234", "p=70000") }
        broken { it.replace("p=51234", "p=abc") }
        // Short token (8 bytes instead of 16).
        broken { it.replaceAfter("t=", base64Url(ByteArray(8)), "&") }
        // Short key (16 bytes instead of 32).
        broken { it.replaceAfter("k=", base64Url(ByteArray(16)), "&") }
        broken { it.replace("s=0123456789abcdef", "s=XYZ") }
    }
}
