package app.simt.echo.transfer

import app.simt.echo.Fixtures
import app.simt.echo.data.FileStore
import app.simt.echo.data.RouteRepository
import app.simt.echo.platform.Endpoint
import app.simt.echo.platform.NfcEvent
import app.simt.echo.platform.PeerTransport
import app.simt.echo.platform.TransferProgress
import app.simt.echo.platform.nfc.NfcInvitationReader
import app.simt.echo.platform.nfc.NfcTransceiver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * W21-04 gate: the real presenter, the real reader, the real service.
 *
 * Nothing here is a stub of the thing under test: `NfcApduPresenter` answers
 * the reader's APDUs, `NfcInvitationReader` assembles the URI, and the two
 * `TransferService` instances seal, offer, fetch, verify and save through the
 * same `BundleCodec` the app uses. The only fakes are the radio (a scripted
 * transceiver) and the network (an in-memory loopback), which is exactly the
 * boundary a host test can own.
 *
 * A passing test here is **not** radio evidence: no Android device was
 * involved, so RF routing remains W21-05's.
 */
class NfcTransferIntegrationTest {

    // ── fakes: the radio and the loopback network, nothing else ──

    /**
     * The peer's side of the radio: every command goes to the real presenter
     * and comes back verbatim. [failOnCommand] cuts the field at that exact
     * exchange, which is how every chunk boundary gets tested.
     */
    private class ScriptedTransceiver(
        private val respond: (ByteArray) -> ByteArray,
        private val failOnCommand: Int = -1,
    ) : NfcTransceiver {
        var commands = 0
        val transcript = mutableListOf<ByteArray>()

        override suspend fun transceive(command: ByteArray): ByteArray {
            commands++
            transcript += command.copyOf()
            if (commands == failOnCommand) error("field lost")
            return respond(command)
        }

        override fun close() = Unit
    }

    /** A radio that never answers until its connection is closed. */
    private class BlockingTransceiver : NfcTransceiver {
        private val released = CompletableDeferred<Unit>()
        var closed = false
            private set

        override suspend fun transceive(command: ByteArray): ByteArray {
            released.await()
            return ByteArray(2)
        }

        override fun close() {
            closed = true
            released.complete(Unit)
        }
    }

    /** In-memory LAN: the "network" between the two services. */
    private class LoopbackTransport : PeerTransport {
        var servedPayload: ByteArray? = null
        var servedToken: String? = null
        var fetches = 0
        val progressFlow = MutableSharedFlow<TransferProgress>(extraBufferCapacity = 8)

        override val progress: Flow<TransferProgress> get() = progressFlow

        override suspend fun offer(payload: ByteArray, token: String): Endpoint {
            servedPayload = payload
            servedToken = token
            return Endpoint("192.168.1.20", 51234)
        }

        override suspend fun fetch(endpoint: Endpoint, token: String): ByteArray {
            fetches++
            check(token == servedToken) { "token mismatch" }
            return servedPayload ?: error("nothing served")
        }

        override fun stopOffer() = Unit
    }

    private class Phone(val routes: RouteRepository, val store: FileStore)

    private val at = { 1_762_000_000_000L }

    private fun sender(transport: PeerTransport, fileSystem: FakeFileSystem): Pair<TransferService, Phone> {
        val store = FileStore(fileSystem, "/sender".toPath())
        val routes = RouteRepository(store)
        routes.save(Fixtures.L_ROUTE)
        fileSystem.createDirectories("/sender-camera".toPath())
        fileSystem.write("/sender-camera/start.jpg".toPath()) { write(byteArrayOf(9, 8, 7)) }
        val service = TransferService(
            transport = transport,
            routes = routes,
            store = store,
            randomBytes = { size -> ByteArray(size) { (it + 1).toByte() } },
            nowMs = at,
            ioDispatcher = Dispatchers.Unconfined,
        )
        return service to Phone(routes, store)
    }

    private fun receiver(transport: PeerTransport): Pair<TransferService, Phone> {
        val store = FileStore(FakeFileSystem(), "/receiver".toPath())
        val routes = RouteRepository(store)
        val service = TransferService(
            transport = transport,
            routes = routes,
            store = store,
            nowMs = at,
            ioDispatcher = Dispatchers.Unconfined,
        )
        return service to Phone(routes, store)
    }

    /** The published snapshot the presenter serves for one offer. */
    private fun snapshotOf(uri: String, generation: Long = 1L, expiresInMs: Long = 60_000L): NfcOfferSnapshot =
        NfcOfferSnapshot(
            generation = generation,
            offerId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
            invitation = uri.encodeToByteArray(),
            expiresAtMonotonicMs = 5_000 + expiresInMs,
        )

    private fun presenter(
        snapshot: () -> NfcOfferSnapshot?,
        nowMs: () -> Long = { 5_000 },
        interactive: () -> Boolean = { true },
    ) = NfcApduPresenter(snapshot = snapshot, nowMs = nowMs, interactive = interactive)

    private class Reading(scope: kotlinx.coroutines.CoroutineScope) {
        val events = mutableListOf<NfcEvent>()
        val reader = NfcInvitationReader(
            scope = scope,
            events = { events += it },
            dispatcher = Dispatchers.Unconfined,
        )

        fun invitation(): String? = events.filterIsInstance<NfcEvent.Invitation>().singleOrNull()?.uri

        fun failure(): PairingFailure? =
            events.filterIsInstance<NfcEvent.ReadFailed>().singleOrNull()?.failure
    }

    // ── the whole handshake, end to end ──────────────────────────

    @Test
    fun aTapMovesTheSelectedExportIntact() = runTest {
        val transport = LoopbackTransport()
        val (serviceA, phoneA) = sender(transport, FakeFileSystem())
        val (serviceB, phoneB) = receiver(transport)

        val selection = serviceA.defaultSelectionFor(Fixtures.L_ROUTE.routeId)
            .copy(includeStartPhoto = false)
        val offer = serviceA.offer(Fixtures.L_ROUTE.routeId, selection = selection)
        val apdu = presenter({ snapshotOf(offer.qrUri, generation = serviceA.offerGeneration) })

        val reading = Reading(this)
        reading.reader.arm(1)
        reading.reader.start(1) { ScriptedTransceiver(apdu::respond) }
        advanceUntilIdle()

        val uri = reading.invitation()
        assertEquals(offer.qrUri, uri, "the radio must carry the very same v1 invitation")

        val body = serviceB.fetchPreview(uri!!)
        serviceB.saveImported(body)
        advanceUntilIdle()

        assertEquals(Fixtures.L_ROUTE.name, phoneB.routes.get(Fixtures.L_ROUTE.routeId)?.name)
        assertEquals(
            Fixtures.L_ROUTE.landmarks.size,
            phoneB.routes.get(Fixtures.L_ROUTE.routeId)?.landmarks?.size,
        )
        // The selected exclusion survived the whole path: no start photo.
        assertNull(
            phoneB.routes.readAsset(Fixtures.L_ROUTE.routeId, "a_start"),
            "an excluded asset must never arrive",
        )
        // …and the sender still holds its own copy untouched.
        assertNotNull(phoneA.routes.get(Fixtures.L_ROUTE.routeId))
    }

    @Test
    fun aGoldenTranscriptDecodesToTheSameInvitation() {
        // A hand-written exchange: fixed offer id, seven-byte invitation.
        val invitation = "ECHOOK!"
        val apdu = presenter({
            NfcOfferSnapshot(
                generation = 7,
                offerId = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88.toByte()),
                invitation = invitation.encodeToByteArray(),
                expiresAtMonotonicMs = 60_000,
            )
        })

        // Encoders are the protocol's; the expected bytes below are written
        // out by hand so the decoder cannot agree with a bug in itself.
        val select = apdu.respond(NfcApduProtocol.selectCommand())
        assertEquals(
            "45 43 48 4F 01 11 22 33 44 55 66 77 88 00 07 90 00",
            select.hex(),
            "SELECT must answer magic, version, offer id, length and OK",
        )
        assertEquals(
            "00 A4 04 00 06 F0 45 43 48 4F 01 00",
            NfcApduProtocol.selectCommand().hex(),
        )

        val read = apdu.respond(NfcApduProtocol.readCommand(offset = 0, count = invitation.length))
        assertEquals("00 B0 00 00 07", NfcApduProtocol.readCommand(0, 7).hex())
        assertEquals("45 43 48 4F 4F 4B 21 90 00", read.hex())

        // Independent decode: header fields and data read without the
        // assembler, then compared to the invitation the presenter holds.
        val header = select.dropLast(2)
        assertEquals("ECHO", header.take(4).toByteArray().decodeToString())
        assertEquals(1, header[4].toInt())
        val declared = ((header[13].toInt() and 0xFF) shl 8) or (header[14].toInt() and 0xFF)
        assertEquals(invitation.length, declared)
        assertEquals(invitation, read.dropLast(2).toByteArray().decodeToString())
    }

    @Test
    fun aLongInvitationCrossesTheChunkBoundaryWhole() = runTest {
        val transport = LoopbackTransport()
        val (serviceA, senderPhone) = sender(transport, FakeFileSystem())
        // A long Unicode name pushes one invitation past a single READ, so
        // the chunk boundary is exercised on the real wire format.
        senderPhone.routes.save(
            Fixtures.L_ROUTE.copy(
                routeId = "long-name",
                name = "É".repeat(30) + " route with a long Unicode name",
            ),
        )
        val offer = serviceA.offer("long-name")
        assertTrue(offer.qrUri.encodeToByteArray().size > NfcApduProtocol.MAX_READ_BYTES)

        val apdu = presenter({ snapshotOf(offer.qrUri) })
        val transceiver = ScriptedTransceiver(apdu::respond)
        val reading = Reading(this)
        reading.reader.arm(1)
        reading.reader.start(1) { transceiver }
        advanceUntilIdle()

        assertEquals(
            offer.qrUri,
            reading.invitation(),
            "a multi-READ invitation must reassemble byte for byte",
        )
        // One SELECT, then exactly ceil(bytes / 224) READs.
        val expectedReads = (offer.qrUri.encodeToByteArray().size + NfcApduProtocol.MAX_READ_BYTES - 1) /
            NfcApduProtocol.MAX_READ_BYTES
        assertEquals(1 + expectedReads, transceiver.commands)
    }

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

/** Lowercase hex with single-space separators, for readable golden strings. */
private fun ByteArray.hex(): String = joinToString(" ") { b ->
    val v = b.toInt() and 0xFF
    v.toString(16).uppercase().padStart(2, '0')
}
