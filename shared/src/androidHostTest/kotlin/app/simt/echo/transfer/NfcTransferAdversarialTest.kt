package app.simt.echo.transfer

import app.simt.echo.Fixtures
import app.simt.echo.platform.NfcEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okio.fakefilesystem.FakeFileSystem

/**
 * W21-04 adversarial half: the handshake that must refuse.
 *
 * Each case drives the real presenter, reader and service through an
 * interruption a real pair of phones can produce — a lost field at every chunk,
 * a replaced offer, an expired or locked phone, a stale stop, a callback storm,
 * a wrong key, a malformed payload. Nothing may yield a partial invitation, a
 * fetch or a saved route.
 */
class NfcTransferAdversarialTest {

    // ── adversarial boundaries ───────────────────────────────────

    @Test
    fun theFieldCanDropAtEveryChunkAndNeverYieldsAPartialInvitation() = runTest {
        val transport = LoopbackTransport()
        val (serviceA, _) = sender(transport, FakeFileSystem())
        val offer = serviceA.offer(Fixtures.L_ROUTE.routeId)
        val (serviceB, phoneB) = receiver(transport)
        val totalCommands = 1 +
            (offer.qrUri.encodeToByteArray().size + NfcApduProtocol.MAX_READ_BYTES - 1) /
            NfcApduProtocol.MAX_READ_BYTES

        for (cut in 1..totalCommands) {
            val apdu = presenter({ snapshotOf(offer.qrUri) })
            val reading = Reading(this)
            reading.reader.arm(cut.toLong())
            reading.reader.start(cut.toLong()) {
                ScriptedTransceiver(apdu::respond, failOnCommand = cut)
            }
            advanceUntilIdle()

            assertNull(reading.invitation(), "cut at command $cut must not yield an invitation")
            assertEquals(
                PairingFailure.Interrupted,
                reading.failure(),
                "a lost field is retryable, not a bad route",
            )
            assertNull(phoneB.routes.get(Fixtures.L_ROUTE.routeId))
            assertEquals(0, transport.fetches, "no bytes move on an incomplete read")
            reading.reader.close()
        }
        assertFalse(phoneB.store.exists("routes/${Fixtures.L_ROUTE.routeId}.json"))
    }

    @Test
    fun aReplacedOfferInvalidatesTheTransactionMidRead() = runTest {
        val transport = LoopbackTransport()
        val (serviceA, _) = sender(transport, FakeFileSystem())
        val first = serviceA.offer(Fixtures.L_ROUTE.routeId)
        val second = serviceA.offer(Fixtures.L_ROUTE.routeId)
        assertTrue(first.qrUri != second.qrUri, "a re-offer seals new bytes")

        var calls = 0
        val apdu = presenter(
            snapshot = {
                // SELECT sees the first offer; every READ sees its replacement.
                calls += 1
                if (calls == 1) {
                    snapshotOf(first.qrUri, generation = 1L)
                } else {
                    snapshotOf(second.qrUri, generation = 2L)
                }
            },
        )
        val reading = Reading(this)
        reading.reader.arm(1)
        reading.reader.start(1) { ScriptedTransceiver(apdu::respond) }
        advanceUntilIdle()

        assertNull(reading.invitation(), "two offers must never be concatenated")
        assertEquals(PairingFailure.OfferExpired, reading.failure())
    }

    @Test
    fun anExpiredOfferAndANonInteractivePhoneServeNothing() = runTest {
        val expired = presenter(
            snapshot = { snapshotOf("simt-echo://r?v=1&h=192.168.1.5&p=1&t=a&k=b&s=c&n=d", expiresInMs = -1_000) },
        )
        val expiredReading = Reading(this)
        expiredReading.reader.arm(1)
        expiredReading.reader.start(1) { ScriptedTransceiver(expired::respond) }
        advanceUntilIdle()
        assertNull(expiredReading.invitation())
        assertEquals(PairingFailure.OfferExpired, expiredReading.failure())

        // Screen off / background: the same offer is not served at all.
        val locked = presenter(
            snapshot = { snapshotOf("simt-echo://r?v=1&h=192.168.1.5&p=1&t=a&k=b&s=c&n=d") },
            interactive = { false },
        )
        val lockedReading = Reading(this)
        lockedReading.reader.arm(2)
        lockedReading.reader.start(2) { ScriptedTransceiver(locked::respond) }
        advanceUntilIdle()
        assertNull(lockedReading.invitation())
        assertEquals(PairingFailure.OfferExpired, lockedReading.failure())
    }

    @Test
    fun armingANewAttemptInterruptsTheBlockedExchange() = runTest {
        val reading = Reading(this)
        val blocked = BlockingTransceiver()
        reading.reader.arm(1)
        reading.reader.start(1) { blocked }
        // A reader connection reports `Reading` before any byte arrives, and
        // nothing beyond that has happened while the exchange is blocked.
        assertEquals(listOf(true), reading.events.map { it is NfcEvent.Reading })

        // Switching methods arms a new attempt. Cancelling the job cannot
        // unblock a transceive inside the tag API: only closing does.
        reading.reader.arm(2)
        advanceUntilIdle()

        assertTrue(blocked.closed, "arming must close the replaced connection")
        assertTrue(reading.events.none { it is NfcEvent.Invitation })
        assertNull(reading.failure(), "a replaced attempt reports no failure")
    }

    @Test
    fun aCallbackStormOpensOneConnection() = runTest {
        val transport = LoopbackTransport()
        val (serviceA, _) = sender(transport, FakeFileSystem())
        val offer = serviceA.offer(Fixtures.L_ROUTE.routeId)
        val apdu = presenter({ snapshotOf(offer.qrUri) })
        val gate = CompletableDeferred<Unit>()
        var opens = 0
        val reading = Reading(this)
        reading.reader.arm(1)

        reading.reader.start(1) {
            opens++
            gate.await()
            ScriptedTransceiver(apdu::respond)
        }
        // Two more tag callbacks while the first is still arriving.
        reading.reader.start(1) { opens++; ScriptedTransceiver(apdu::respond) }
        reading.reader.start(1) { opens++; ScriptedTransceiver(apdu::respond) }
        assertTrue(gate.complete(Unit))
        advanceUntilIdle()

        assertEquals(1, opens, "a storm is dropped, not queued")
        assertEquals(offer.qrUri, reading.invitation())
        assertEquals(1, reading.events.count { it is NfcEvent.Invitation })
    }

    @Test
    fun aStaleStopCannotCancelANewerAttempt() = runTest {
        val transport = LoopbackTransport()
        val (serviceA, _) = sender(transport, FakeFileSystem())
        val offer = serviceA.offer(Fixtures.L_ROUTE.routeId)
        val apdu = presenter({ snapshotOf(offer.qrUri) })
        val reading = Reading(this)
        reading.reader.arm(2)
        val gate = CompletableDeferred<Unit>()
        reading.reader.start(2) {
            gate.await()
            ScriptedTransceiver(apdu::respond)
        }

        // Attempt 1 was replaced before it started: its stop is a no-op.
        reading.reader.stopReading(1)
        assertTrue(gate.complete(Unit))
        advanceUntilIdle()

        assertEquals(offer.qrUri, reading.invitation(), "the live attempt keeps reading")
    }

    @Test
    fun aWrongKeyFailsBeforeAnythingIsSaved() = runTest {
        val transport = LoopbackTransport()
        val (serviceA, _) = sender(transport, FakeFileSystem())
        val (serviceB, phoneB) = receiver(transport)
        val offer = serviceA.offer(Fixtures.L_ROUTE.routeId)
        val apdu = presenter({ snapshotOf(offer.qrUri) })
        val reading = Reading(this)
        reading.reader.arm(1)
        reading.reader.start(1) { ScriptedTransceiver(apdu::respond) }
        advanceUntilIdle()

        // A tapped invitation whose key is not the sent one: the radio read
        // succeeds, decryption must not.
        val payload = QrPayload.parse(reading.invitation()!!).getOrThrow()
        val tampered = payload.copy(key = ByteArray(32) { 7 }).build()
        assertFailsWith<Exception> { serviceB.fetchPreview(tampered) }
        assertNull(phoneB.routes.get(Fixtures.L_ROUTE.routeId))
        assertFalse(phoneB.store.exists("routes/${Fixtures.L_ROUTE.routeId}.json"))
    }

    @Test
    fun aMalformedRadioPayloadIsATypedFailureAndNeverFetches() = runTest {
        val transport = LoopbackTransport()
        val (_, _) = receiver(transport)
        // An empty invitation is never served at all: the presenter refuses a
        // zero-byte offer, which is a typed offer failure rather than a
        // malformed one. Both stop before any network work.
        val empty = Reading(this)
        empty.reader.arm(0)
        empty.reader.start(0) {
            ScriptedTransceiver(
                presenter({
                    NfcOfferSnapshot(
                        generation = 1,
                        offerId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
                        invitation = ByteArray(0),
                        expiresAtMonotonicMs = 60_000,
                    )
                })::respond,
            )
        }
        advanceUntilIdle()
        assertNull(empty.invitation())
        assertEquals(PairingFailure.OfferExpired, empty.failure())

        val corpus = listOf(
            "not a uri",
            "https://192.168.1.5/steal",
            "simt-echo://x?v=1",
            "simt-echo://r?v=2&h=192.168.1.5&p=8080&t=AAECAwQFBgcICQoLDA0ODw&k=" +
                "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8&s=0123456789abcdef&n=x",
            "simt-echo://r?v=1&h=192.168.1.5&p=8080&t=AAECAwQFBgcICQoLDA0ODw&k=" +
                "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8&s=0123456789abcdef&n=x#frag",
        )

        corpus.forEachIndexed { index, uri ->
            val apdu = presenter({
                NfcOfferSnapshot(
                    generation = index.toLong() + 1,
                    offerId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
                    invitation = uri.encodeToByteArray(),
                    expiresAtMonotonicMs = 60_000,
                )
            })
            val reading = Reading(this)
            reading.reader.arm(index.toLong())
            reading.reader.start(index.toLong()) { ScriptedTransceiver(apdu::respond) }
            advanceUntilIdle()

            assertNull(reading.invitation(), "corpus[$index] must not be an invitation")
            assertEquals(
                PairingFailure.InvalidInvitation,
                reading.failure(),
                "corpus[$index] must fail before any network work",
            )
        }
        assertEquals(0, transport.fetches, "a malformed invitation never starts a transfer")
    }
}
