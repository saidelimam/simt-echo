package app.simt.echo.transfer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * W20-02 gate: one invitation, bounded and strictly parsed.
 *
 * These cases are the reason a second invitation source (NFC) is safe to
 * add: everything a camera or a radio can hand us is rejected unless it is
 * the exact v1 shape this app emits, within 1 024 bytes, with valid UTF-8.
 */
class PairingInvitationTest {

    private val payload = QrPayload(
        host = "192.168.43.17",
        port = 48217,
        token = ByteArray(16) { (it + 1).toByte() },
        key = ByteArray(32) { (it * 5).toByte() },
        sha8Hex = "f3a9c2d1a4b5c6d7",
        name = "Garage → Lift",
    )

    private fun rejection(uri: String) = assertNotNull(
        PairingInvitation.parse(uri).exceptionOrNull(),
        "expected rejection: ${uri.take(60)}",
    )

    @Test
    fun unicodeNamesRoundTripWhole() {
        // Arabic, emoji and accents must survive parse → build → parse with
        // the same characters — never replacement glyphs, never truncated
        // mid-surrogate.
        val names = listOf("Garage → Lift", "المصعد", "Café 🚪 2", "🚪🚪🚪🚪")
        names.forEach { name ->
            val parsed = PairingInvitation.parseOrThrow(payload.copy(name = name).build())
            assertEquals(name, parsed.payload.name)
            assertEquals(name, PairingInvitation.parseOrThrow(parsed.uri).payload.name)
        }
    }

    @Test
    fun aNameOfFortyEmojiIsBoundedWithoutBreakingUnicode() {
        val name = "🚪".repeat(45)
        val built = payload.copy(name = name).build()
        val parsed = PairingInvitation.parseOrThrow(built).payload
        // 40 code points, i.e. 80 UTF-16 units, all still valid pairs.
        assertEquals(40, parsed.name.countCodePoints())
        assertEquals("🚪".repeat(40), parsed.name)
    }

    @Test
    fun legacyV1QrStaysValid() {
        // A camera-produced legacy URI with the old short relay code is
        // *not* legacy-valid — relay codes have always been 8 characters
        // in the worker. The honest legacy sample is the plain LAN one.
        val uri = "simt-echo://r?v=1&h=192.168.1.20&p=51234" +
            "&t=AQIDBAUGBwgJCgsMDQ4PEA&k=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8" +
            "&s=0123456789abcdef&n=Garage%20to%20lift"
        val parsed = PairingInvitation.parseOrThrow(uri)
        assertEquals("192.168.1.20", parsed.payload.host)
        assertEquals(51234, parsed.payload.port)
        assertEquals("Garage to lift", parsed.payload.name)
        assertEquals(uri.encodeToByteArray().size, parsed.bytes.size)
    }

    @Test
    fun byteBoundIsEnforcedOnParseAndOnBuild() {
        val base = payload.build()
        val pad = PairingInvitation.MAX_UTF8_BYTES - base.encodeToByteArray().size - "&x=".length
        val exact = base + "&x=" + "p".repeat(pad)
        // Exactly 1 024 bytes: unknown legacy fields are tolerated inside
        // the bound.
        assertEquals(PairingInvitation.MAX_UTF8_BYTES, exact.encodeToByteArray().size)
        assertEquals(payload, PairingInvitation.parseOrThrow(exact).payload)

        // One byte over is a rejection, not a truncation.
        rejection(exact + "p")
    }

    @Test
    fun duplicateKnownFieldsAlwaysFail() {
        val base = payload.build()
        rejection("$base&h=192.168.1.21")
        rejection("$base&n=other")
        rejection(base.replace("v=1", "v=1&v=1"))
        // Duplicate unknown fields are tolerated for legacy compatibility.
        assertEquals(payload, PairingInvitation.parseOrThrow("$base&x=1&x=2").payload)
    }

    @Test
    fun fragmentsAndMalformedEscapesAndUtf8Fail() {
        rejection("${payload.build()}#frag")
        val base = payload.build()
        rejection(base.replace("n=Garage", "n=%ZZGarage"))
        rejection(base.replace("n=Garage", "n=%4"))
        // %FF is not valid UTF-8 on its own.
        rejection(base.replace("n=Garage", "n=%FF"))
        // A raw non-ASCII byte in the name is not what the builder emits.
        rejection(base.replace("n=Garage%20%E2%86%92", "n=Garage→"))
    }

    @Test
    fun undialableHostsAreRefused() {
        val good = payload.build()
        listOf("127.0.0.1", "0.0.0.0", "255.255.255.255", "224.0.0.1", "127.1.2.3").forEach { host ->
            assertFalse(isDialableIpv4(host), host)
            rejection(good.replace("h=192.168.43.17", "h=$host"))
        }
        // A LAN or hotspot address stays valid — including non-site-local
        // ones a legitimate router may hand out.
        listOf("192.168.43.1", "10.0.0.7", "172.20.3.4", "203.0.113.9").forEach {
            assertTrue(isDialableIpv4(it), it)
        }
    }

    @Test
    fun badPortSecretsAndVersionFail() {
        val base = payload.build()
        rejection(base.replace("p=48217", "p=0"))
        rejection(base.replace("p=48217", "p=65536"))
        rejection(base.replace("p=48217", "p=8x"))
        rejection(base.replaceAfter("t=", base64Url(ByteArray(8)), "&"))
        rejection(base.replaceAfter("k=", base64Url(ByteArray(16)), "&"))
        rejection(base.replace("s=f3a9c2d1a4b5c6d7", "s=f3a9c2d1"))
        rejection(base.replace("v=1", "v=2"))
        rejection(base.replace("v=1", "v="))
    }

    @Test
    fun relayCodeMustMatchTheWorkerAlphabetAndLength() {
        val base = payload.build()
        // Exactly what `worker/src/relay.ts` mints (R12-03: 10 characters).
        val withRelay = PairingInvitation.parseOrThrow("$base&c=ABCD234567").payload
        assertEquals("ABCD234567", withRelay.relayCode)
        // Short, long, lowercase and ambiguous glyphs are all rejected.
        rejection("$base&c=ABC2345")
        rejection("$base&c=ABCD2345678")
        rejection("$base&c=abcd234567")
        rejection("$base&c=ABCDO23456")
    }

    @Test
    fun outboundInvitationFormattingNeverCarriesTheCapability() {
        val parsed = PairingInvitation.parseOrThrow(payload.build())
        val text = parsed.toString()
        assertFalse(text.contains("AQID"), text)
        assertFalse(text.contains(parsed.uri), text)
        // Neither secret appears in either representation (the host is not
        // secret, so this checks the key and token specifically).
        val token = base64Url(parsed.payload.token)
        val key = base64Url(parsed.payload.key)
        listOf(parsed.toString(), parsed.payload.toString()).forEach { formatted ->
            assertFalse(formatted.contains(token), formatted)
            assertFalse(formatted.contains(key), formatted)
        }
        assertTrue(text.contains("bytes="), text)
    }

    private fun String.countCodePoints(): Int {
        var count = 0
        var i = 0
        while (i < length) {
            val paired = this[i].isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()
            i += if (paired) 2 else 1
            count++
        }
        return count
    }
}
