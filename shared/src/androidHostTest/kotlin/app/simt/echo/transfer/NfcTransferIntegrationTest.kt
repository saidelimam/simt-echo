package app.simt.echo.transfer

import app.simt.echo.Fixtures
import app.simt.echo.bundle.BundleCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okio.fakefilesystem.FakeFileSystem

/**
 * W21-04 gate, happy paths: the real presenter, reader and service.
 *
 * Nothing here is a stub of the thing under test: `NfcApduPresenter` answers
 * the reader's APDUs, `NfcInvitationReader` assembles the URI, and the two
 * `TransferService` instances seal, offer, fetch, verify and save through the
 * same `BundleCodec` the app uses. The only fakes are the radio and the network,
 * which is exactly the boundary a host test can own.
 *
 * A passing test here is **not** radio evidence: no Android device was
 * involved, so RF routing remains W21-05's.
 */
class NfcTransferIntegrationTest {

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

    @Test
    fun whenLanIsIsolatedTapWithRelayCodeFallsBackToRelayAndVerifiesBundle() = runTest {
        val lan = LoopbackTransport()
        val relay = RelayStore()
        val (serviceA, _) = sender(lan, FakeFileSystem(), relay = relay, peerRelayQualified = true)
        // A relay offer seals once and uploads the ciphertext under a code.
        val offer = serviceA.offer(Fixtures.L_ROUTE.routeId, useRelay = true)
        val payload = QrPayload.parse(offer.qrUri).getOrThrow()
        assertEquals("ABCD234567", payload.relayCode, "the invitation carries a relay code")
        assertEquals(1, relay.uploads)

        // The real tap: actual presenter, actual reader, actual v1 invitation.
        val apdu = presenter({ snapshotOf(offer.qrUri, generation = serviceA.offerGeneration) })
        val reading = Reading(this)
        reading.reader.arm(1)
        reading.reader.start(1) { ScriptedTransceiver(apdu::respond) }
        advanceUntilIdle()
        val uri = reading.invitation()
        assertEquals(offer.qrUri, uri)

        // The receiver is on Wi-Fi that isolates peers: the LAN dial fails.
        val isolated = IsolatedTransport()
        val (serviceB, phoneB) = receiver(isolated, relay = relay, peerRelayQualified = true)
        val body = serviceB.fetchPreview(uri!!)
        serviceB.saveImported(body)
        advanceUntilIdle()

        assertEquals(1, isolated.fetches, "the LAN stays the first choice")
        assertEquals(1, relay.downloads, "an unreachable peer falls back to the relay")
        // The relay copy is the same AES-GCM bundle: it verifies, and only
        // then does the route reach the receiving phone.
        assertEquals(payload.sha8Hex, BundleCodec.sha8Hex(body))
        assertEquals(Fixtures.L_ROUTE.name, phoneB.routes.get(Fixtures.L_ROUTE.routeId)?.name)
    }
}
