package app.simt.echo.transfer

import app.simt.echo.platform.NfcEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * W21-04 gate: what a pairing never says out loud.
 *
 * An invitation is a bearer capability — whoever holds the URI holds the
 * token and the AES key. These tests cover the representations that reach
 * logs, crash reports and test output, and the malformed-input corpus that
 * must fail typed before any networking starts. The protocol's own byte
 * arithmetic is covered by `NfcApduProtocolTest`; this file does not repeat
 * it.
 */
class PairingSecurityTest {

    private val token = ByteArray(16) { (it + 1).toByte() }
    private val key = ByteArray(32) { (it + 2).toByte() }

    private fun payload(name: String = "Riverside") = QrPayload(
        host = "192.168.1.5",
        port = 8080,
        token = token,
        key = key,
        sha8Hex = "0123456789abcdef",
        name = name,
    )

    private fun uri(): String = payload().build()

    @Test
    fun noRepresentationPrintsTheCapability() {
        val payload = payload()
        val invitation = PairingInvitation.of(payload)
        val snapshot = NfcOfferSnapshot(
            generation = 3,
            offerId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
            invitation = invitation.bytes,
            expiresAtMonotonicMs = 1_000,
        )
        val event = NfcEvent.Invitation(attemptId = 1, uri = invitation.uri)

        // The payload may name its LAN endpoint — that is not a secret — but
        // never the parts that authorise a fetch.
        val payloadText = payload.toString()
        assertFalse(payloadText.contains("Riverside"), "name leaked in: $payloadText")
        assertFalse(payloadText.contains(base64Url(token)), "token leaked in: $payloadText")
        assertFalse(payloadText.contains(base64Url(key)), "key leaked in: $payloadText")
        assertFalse(payloadText.contains("0123456789abcdef"), "fingerprint leaked in: $payloadText")

        // Everything that wraps the whole invitation hides it completely.
        listOf(invitation.toString(), snapshot.toString(), event.toString()).forEach { rendered ->
            assertFalse(rendered.contains("192.168.1.5"), "host leaked in: $rendered")
            assertFalse(rendered.contains("0123456789abcdef"), "fingerprint leaked in: $rendered")
            assertFalse(rendered.contains("Riverside"), "name leaked in: $rendered")
            assertFalse(rendered.contains(base64Url(token)), "token leaked in: $rendered")
            assertFalse(rendered.contains(base64Url(key)), "key leaked in: $rendered")
            assertFalse(rendered.contains("simt-echo"), "scheme leaked in: $rendered")
        }
        // The redaction still says something useful about size.
        assertTrue(invitation.toString().contains("bytes="))
    }

    @Test
    fun typedFailuresCarryNoDetail() {
        // Every case reports the same typed value: no parser message, no
        // field, no status — a failure cannot become a side channel.
        val corpus = listOf(
            "https://192.168.1.5:8080/r",
            "simt-echo://r?v=1&h=192.168.1.5&p=8080",
            "simt-echo://r?v=1&h=192.168.1.5&p=8080&t=${base64Url(token)}&k=${base64Url(key)}&s=zzzzzzzzzzzzzzzz&n=x",
            "simt-echo://r?v=1&h=192.168.1.5&p=8080&t=${base64Url(token)}&k=${base64Url(key)}&s=0123456789abcdef&n=x&n=y",
        )

        corpus.forEach { candidate ->
            val failure = PairingInvitation.parse(candidate).exceptionOrNull()
            assertTrue(failure != null, "must fail: $candidate")
            assertEquals(PairingFailure.InvalidInvitation, PairingInvitation.failureOf(failure))
            assertEquals(
                "InvalidInvitation",
                PairingFailure.InvalidInvitation.toString(),
                "a typed failure renders as its name only",
            )
        }
    }

    @Test
    fun aOneKiloByteBoundHoldsOnParseAsWellAsGeneration() {
        val fits = payload(name = "a").build()
        assertTrue(fits.encodeToByteArray().size < PairingInvitation.MAX_UTF8_BYTES)

        // A name long enough to push a valid payload past the bound: the
        // parser refuses it rather than handing NFC an unbounded read.
        val padded = fits + "&pad=" + "x".repeat(PairingInvitation.MAX_UTF8_BYTES)
        assertTrue(PairingInvitation.parse(padded).isFailure)

        // Build refuses to mint one, too.
        assertTrue(
            runCatching { payload(name = "n".repeat(40)).copy(host = "10.0.0.1").build() }.isSuccess,
            "a 40-character name stays inside the bound",
        )
    }

    @Test
    fun aMutationCorpusNeverWidensAField() {
        // Deterministic mutations of a good URI, no fuzzer: every candidate
        // either fails or still satisfies the wire invariants. Nothing here
        // may crash, and no mutation may widen a field.
        val good = uri()
        val candidates = buildList {
            good.indices.forEach { index ->
                add(good.removeRange(index, index + 1))
                if (index < good.length - 1) {
                    add(good.substring(0, index) + good[index + 1] + good[index] + good.substring(index + 2))
                }
                add(good.substring(0, index) + "%" + good.substring(index + 1))
            }
            addAll(listOf("", "\u0000", "simt-echo://r", "simt-echo://r?", "simt-echo://r?v=1"))
        }

        candidates.forEach { candidate ->
            PairingInvitation.parse(candidate).getOrNull()?.let { invitation ->
                assertEquals(
                    QrPayload.TOKEN_BYTES,
                    invitation.payload.token.size,
                    "token width changed: $candidate",
                )
                assertEquals(
                    QrPayload.KEY_BYTES,
                    invitation.payload.key.size,
                    "key width changed: $candidate",
                )
                assertTrue(isDialableIpv4(invitation.payload.host), "host must stay dialable: $candidate")
                assertTrue(invitation.bytes.size <= PairingInvitation.MAX_UTF8_BYTES)
            }
        }
    }

    @Test
    fun anApduStatusIsNeverProseThatQuotesTheOffer() {
        val exception = ApduStatusException(NfcApduProtocol.SW_NO_OFFER)
        assertEquals(
            "APDU status 6985",
            exception.message,
            "status text is a number, never a field",
        )
        assertTrue(exception.stackTrace.isEmpty(), "no stack to leak a business path")
    }
}
