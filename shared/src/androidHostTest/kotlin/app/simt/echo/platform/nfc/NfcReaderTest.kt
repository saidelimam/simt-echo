package app.simt.echo.platform.nfc

import app.simt.echo.platform.NfcEvent
import app.simt.echo.transfer.NfcApduPresenter
import app.simt.echo.transfer.NfcApduProtocol
import app.simt.echo.transfer.NfcOfferSnapshot
import app.simt.echo.transfer.PairingFailure
import app.simt.echo.transfer.QrPayload
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W20-05 gate: one invitation, read without blocking the UI.
 *
 * A scripted transceiver stands in for the tag API, so every case here is
 * deterministic: a complete read, bytes split across chunk boundaries, a
 * wrong tag, field loss at each command, malformed responses, a callback
 * storm, cancellation and a timeout that must not leak a connection.
 *
 * No simulator or host test is a phone tap — RF routing stays W21-05
 * device evidence. What is proved here is that the reader emits exactly
 * one typed outcome per attempt and always closes what it opened.
 */
class NfcReaderTest {

    private val events = mutableListOf<NfcEvent>()

    /** A phone's presenter, as the peer side really answers. */
    private class PhoneTransceiver(private val presenter: NfcApduPresenter) : NfcTransceiver {
        var closed = false
            private set

        override suspend fun transceive(command: ByteArray): ByteArray = presenter.respond(command)

        override fun close() {
            closed = true
        }
    }

    private fun phone(invitation: ByteArray, expiresAt: Long = Long.MAX_VALUE): NfcApduPresenter =
        NfcApduPresenter(
            snapshot = {
                NfcOfferSnapshot(
                    generation = 1L,
                    offerId = ByteArray(NfcApduProtocol.OFFER_ID_BYTES) { (it + 1).toByte() },
                    invitation = invitation,
                    expiresAtMonotonicMs = expiresAt,
                )
            },
            nowMs = { 0L },
            interactive = { true },
        )

    private fun invitationUri(name: String = "Lobby to lift"): String = QrPayload(
        host = "192.168.43.17",
        port = 48217,
        token = ByteArray(16) { (it + 1).toByte() },
        key = ByteArray(32) { (it * 3).toByte() },
        sha8Hex = "f3a9c2d1a4b5c6d7",
        name = name,
    ).build()

    private fun reader(
        scope: kotlinx.coroutines.CoroutineScope,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        commandTimeoutMs: Long = 50L,
        exchangeTimeoutMs: Long = 200L,
    ) = NfcInvitationReader(
        scope = scope,
        events = { events += it },
        dispatcher = dispatcher,
        commandTimeoutMs = commandTimeoutMs,
        exchangeTimeoutMs = exchangeTimeoutMs,
    )

    private fun invitations() = events.filterIsInstance<NfcEvent.Invitation>()

    private fun failures() = events.filterIsInstance<NfcEvent.ReadFailed>().map { it.failure }

    @Test
    fun aCompleteInvitationIsEmittedExactlyOnce() = runTest {
        val uri = invitationUri()
        val phone = PhoneTransceiver(phone(uri.encodeToByteArray()))
        val reader = reader(backgroundScope, UnconfinedTestDispatcher(testScheduler))

        reader.arm(attemptId = 1L)
        reader.start(attemptId = 1L) { phone }

        assertEquals(listOf(NfcEvent.Reading(1L)), events.filterIsInstance<NfcEvent.Reading>())
        assertEquals(listOf(uri), invitations().map { it.uri })
        assertEquals(1, invitations().size, "one tap is one invitation")
        assertTrue(phone.closed, "the connection is always closed")
    }

    @Test
    fun multiByteUtf8SplitAcrossChunksSurvives() = runTest {
        // A 40-emoji name percent-encodes past 224 bytes, so the invitation
        // crosses a chunk boundary in the middle of an escape sequence.
        val uri = invitationUri(name = "🚪".repeat(40))
        assertTrue(uri.encodeToByteArray().size > NfcApduProtocol.MAX_READ_BYTES, "test needs two chunks")

        val reader = reader(backgroundScope, UnconfinedTestDispatcher(testScheduler))
        reader.arm(attemptId = 1L)
        reader.start(attemptId = 1L) { PhoneTransceiver(phone(uri.encodeToByteArray())) }

        assertEquals(listOf(uri), invitations().map { it.uri })
        assertEquals("🚪".repeat(40), QrPayload.parse(invitations().single().uri).getOrThrow().name)
    }

    @Test
    fun aWrongTagIsNotAnEchoShare() = runTest {
        // The tag answers ECHO's SELECT with a different AID's status.
        val stranger = object : NfcTransceiver {
            override suspend fun transceive(command: ByteArray): ByteArray =
                byteArrayOf(0x6A, 0x82.toByte())

            override fun close() = Unit
        }
        val reader = reader(backgroundScope, UnconfinedTestDispatcher(testScheduler))
        reader.arm(attemptId = 1L)
        reader.start(attemptId = 1L) { stranger }

        assertEquals(listOf(PairingFailure.InvalidInvitation), failures())
        assertTrue(invitations().isEmpty())
    }

    @Test
    fun fieldLossAtEveryCommandIsAnInterruption() = runTest {
        val uri = invitationUri(name = "🚪".repeat(40))
        val bytes = uri.encodeToByteArray()
        val chunkCount = (bytes.size + NfcApduProtocol.MAX_READ_BYTES - 1) / NfcApduProtocol.MAX_READ_BYTES

        // Command 0 is SELECT, 1..n are the READs.
        for (failAt in 0..chunkCount) {
            events.clear()
            val peer = PhoneTransceiver(phone(bytes))
            val lossy = object : NfcTransceiver {
                private var index = 0
                override suspend fun transceive(command: ByteArray): ByteArray {
                    if (index++ == failAt) throw IOException("field lost")
                    return peer.transceive(command)
                }

                override fun close() {
                    peer.close()
                }
            }
            val reader = reader(backgroundScope, UnconfinedTestDispatcher(testScheduler))
            reader.arm(attemptId = failAt.toLong())
            reader.start(attemptId = failAt.toLong()) { lossy }

            assertEquals(
                listOf(PairingFailure.Interrupted),
                failures(),
                "field loss at command $failAt must be a retryable interruption",
            )
            assertTrue(invitations().isEmpty(), "no partial invitation is ever emitted")
        }
    }

    @Test
    fun aTagWithoutTheEchoApplicationIsReportedNotIgnored() = runTest {
        // A standard NFC tag has no IsoDep interface. Ignoring it left the
        // receiver silent on a tap that did happen (R14-04).
        val reader = reader(backgroundScope, UnconfinedTestDispatcher(testScheduler))
        reader.arm(attemptId = 1L)
        reader.start(attemptId = 1L) { null as NfcTransceiver? }
        runCurrent()

        assertEquals(listOf(PairingFailure.InvalidInvitation), failures())
        assertTrue(invitations().isEmpty())
    }

    @Test
    fun malformedResponsesFailBeforeAnyInvitation() = runTest {
        // A SELECT cut short of its status bytes.
        val truncated = object : NfcTransceiver {
            override suspend fun transceive(command: ByteArray): ByteArray = ByteArray(10)
            override fun close() = Unit
        }
        val reader = reader(backgroundScope, UnconfinedTestDispatcher(testScheduler))
        reader.arm(attemptId = 1L)
        reader.start(attemptId = 1L) { truncated }
        assertTrue(invitations().isEmpty())
        assertEquals(1, failures().size, "a malformed SELECT is one typed failure")

        // A READ that returns more bytes than it was asked for.
        events.clear()
        val uri = invitationUri()
        val peer = PhoneTransceiver(phone(uri.encodeToByteArray()))
        val padded = object : NfcTransceiver {
            override suspend fun transceive(command: ByteArray): ByteArray {
                val reply = peer.transceive(command)
                return if (NfcApduProtocol.statusOf(reply) == NfcApduProtocol.SW_OK &&
                    command.size == 5
                ) {
                    reply.copyOf(reply.size + 1)
                } else {
                    reply
                }
            }

            override fun close() {
                peer.close()
            }
        }
        val second = reader(backgroundScope, UnconfinedTestDispatcher(testScheduler))
        second.arm(attemptId = 2L)
        second.start(attemptId = 2L) { padded }
        assertTrue(invitations().isEmpty())
        assertEquals(listOf(PairingFailure.Interrupted), failures())
    }

    @Test
    fun anInvitationThatIsNotAnEchoUriIsRejected() = runTest {
        // A structurally valid APDU exchange carrying bytes that are not
        // an ECHO invitation at all.
        val payload = "hello".encodeToByteArray()
        val selectResponse = NfcApduProtocol.MAGIC +
            byteArrayOf(NfcApduProtocol.VERSION.toByte()) +
            ByteArray(NfcApduProtocol.OFFER_ID_BYTES) { 7 } +
            byteArrayOf(0x00, payload.size.toByte()) +
            byteArrayOf(0x90.toByte(), 0x00)
        val foreign = object : NfcTransceiver {
            override suspend fun transceive(command: ByteArray): ByteArray = when (command[1]) {
                NfcApduProtocol.INS_SELECT.toByte() -> selectResponse
                else -> payload + byteArrayOf(0x90.toByte(), 0x00)
            }

            override fun close() = Unit
        }
        val reader = reader(backgroundScope, UnconfinedTestDispatcher(testScheduler))
        reader.arm(attemptId = 1L)
        reader.start(attemptId = 1L) { foreign }

        assertEquals(listOf(PairingFailure.InvalidInvitation), failures())
        assertTrue(invitations().isEmpty())
    }

    @Test
    fun aCallbackStormOpensExactlyOneConnection() = runTest {
        var opens = 0
        val gate = CompletableDeferred<Unit>()
        val blocked = object : NfcTransceiver {
            override suspend fun transceive(command: ByteArray): ByteArray {
                gate.await()
                return ByteArray(0)
            }

            override fun close() {
                gate.cancel()
            }
        }
        val reader = reader(backgroundScope, UnconfinedTestDispatcher(testScheduler))
        reader.arm(attemptId = 1L)
        repeat(5) {
            reader.start(attemptId = 1L) {
                opens++
                blocked
            }
        }
        runCurrent()
        assertEquals(1, opens, "one tap must not multiply connections")
        reader.stopReading(1L)
    }

    @Test
    fun cancellingClosesABlockedTransceive() = runTest {
        val gate = CompletableDeferred<Unit>()
        var closed = false
        val blocked = object : NfcTransceiver {
            override suspend fun transceive(command: ByteArray): ByteArray {
                gate.await()
                return ByteArray(0)
            }

            override fun close() {
                closed = true
                gate.cancel()
            }
        }
        val reader = reader(backgroundScope, UnconfinedTestDispatcher(testScheduler))
        reader.arm(attemptId = 1L)
        reader.start(attemptId = 1L) { blocked }
        runCurrent()

        // The close happens on the caller's thread: a coroutine timeout
        // alone cannot interrupt blocking tag I/O.
        reader.stopReading(1L)
        runCurrent()
        assertTrue(closed, "cancelling must close the connection")
        assertTrue(failures().isEmpty(), "a cancelled attempt reports nothing")
    }

    @Test
    fun anExchangeThatNeverCompletesTimesOutAndReleasesTheConnection() = runTest {
        var closed = false
        val blocked = object : NfcTransceiver {
            override suspend fun transceive(command: ByteArray): ByteArray {
                kotlinx.coroutines.awaitCancellation()
            }

            override fun close() {
                closed = true
            }
        }
        val reader = reader(
            backgroundScope,
            UnconfinedTestDispatcher(testScheduler),
            exchangeTimeoutMs = 100L,
        )
        reader.arm(attemptId = 1L)
        reader.start(attemptId = 1L) { blocked }
        runCurrent()

        advanceTimeBy(150L)
        runCurrent()

        assertEquals(listOf(PairingFailure.Timeout), failures())
        assertTrue(closed, "the timeout path must close the connection, not leak it")
        assertTrue(invitations().isEmpty())
    }

    @Test
    fun aTapForAReplacedAttemptCanNeverReport() = runTest {
        val gate = CompletableDeferred<Unit>()
        val late = object : NfcTransceiver {
            override suspend fun transceive(command: ByteArray): ByteArray {
                gate.await()
                return ByteArray(0)
            }

            override fun close() {
                gate.cancel()
            }
        }
        val reader = reader(backgroundScope, UnconfinedTestDispatcher(testScheduler))
        reader.arm(attemptId = 1L)
        reader.start(attemptId = 1L) { late }
        runCurrent()

        // The user switched method: attempt 1 is replaced by attempt 2.
        reader.arm(attemptId = 2L)
        runCurrent()
        reader.start(attemptId = 1L) { error("a stale tap must not open a connection") }
        runCurrent()

        assertTrue(invitations().isEmpty())
        assertTrue(failures().isEmpty(), "a replaced attempt never reports")
    }
}
