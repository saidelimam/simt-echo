package app.simt.echo.platform.nfc

import app.simt.echo.platform.NfcEvent
import app.simt.echo.transfer.ApduStatusException
import app.simt.echo.transfer.NfcApduProtocol
import app.simt.echo.transfer.NfcInvitationAssembler
import app.simt.echo.transfer.PairingFailure
import app.simt.echo.transfer.PairingInvitation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout

/**
 * One connected peer, as the reader needs it (plan 0005 §4.4).
 *
 * A seam, not a convenience: the production implementation is an `IsoDep`
 * wrapper, and the host gate drives a scripted one. Closing from any thread
 * must interrupt a blocked exchange — a coroutine timeout cannot cancel
 * blocking I/O on its own, so [close] is what actually unblocks the reader.
 */
interface NfcTransceiver {
    /** Blocking command exchange; always runs on IO, never on the UI thread. */
    suspend fun transceive(command: ByteArray): ByteArray

    /** Closes the connection, interrupting any blocked exchange. */
    fun close()
}

/** Per-command bound: one APDU must not hang the exchange. */
const val NFC_COMMAND_TIMEOUT_MS = 1_500L

/** Whole-exchange bound: SELECT plus every READ. */
const val NFC_EXCHANGE_TIMEOUT_MS = 5_000L

/** How long NFC receive waits for a tap before offering QR again. */
const val NFC_READER_IDLE_TIMEOUT_MS = 20_000L

/**
 * Reads one complete ECHO invitation from a foreground reader (plan 0005
 * §4.4/§4.5).
 *
 * The reader never fetches, saves or parses beyond the invitation: it emits
 * typed events tagged with its attempt, and the screen decides what to do.
 * A wrong tag has no network side effect and launches nothing.
 *
 * Bounded in both directions: every command is timeout-guarded, the whole
 * exchange is bounded, only one connection can exist at a time (a callback
 * storm is dropped, not queued), and a close from another thread interrupts
 * a blocked `transceive`.
 */
class NfcInvitationReader(
    private val scope: CoroutineScope,
    private val events: (NfcEvent) -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val commandTimeoutMs: Long = NFC_COMMAND_TIMEOUT_MS,
    private val exchangeTimeoutMs: Long = NFC_EXCHANGE_TIMEOUT_MS,
    private val validate: (String) -> Result<PairingInvitation> = { PairingInvitation.parse(it) },
) {
    private val lock = Mutex()
    private var active: Long? = null
    private var job: Job? = null

    @Volatile
    private var connection: NfcTransceiver? = null

    /**
     * Arms [attemptId] as the only attempt that may read. Any previous
     * attempt is cancelled, so its in-flight exchange can never report.
     */
    fun arm(attemptId: Long) {
        synchronized(this) {
            if (active == attemptId) return
            active = attemptId
            job?.cancel()
            job = null
        }
    }

    /**
     * Reads for an armed [attemptId]. A tag callback for any other attempt
     * is dropped: only the currently armed attempt may open a connection,
     * and only one connection may exist at a time.
     */
    fun start(attemptId: Long, open: suspend () -> NfcTransceiver?) {
        synchronized(this) {
            if (active != attemptId) return
            if (job?.isActive == true) return
            job = scope.launch(dispatcher) { exchange(attemptId, open) }
        }
    }

    /** Disarms [attemptId]; a stale stop cannot cancel a newer attempt. */
    fun stopReading(attemptId: Long) {
        var socket: NfcTransceiver? = null
        val cancelled = synchronized(this) {
            if (active != attemptId) return
            active = null
            socket = connection
            job?.cancel()
            job = null
            true
        }
        if (cancelled) {
            // Closing here, on the caller's thread, is what interrupts a
            // transceive that is blocked inside the tag API.
            runCatching { socket?.close() }
        }
    }

    fun close() {
        var socket: NfcTransceiver? = null
        synchronized(this) {
            active = null
            socket = connection
            job?.cancel()
            job = null
        }
        runCatching { socket?.close() }
    }

    private fun isCurrent(attemptId: Long): Boolean = synchronized(this) {
        active == attemptId
    }

    private suspend fun exchange(attemptId: Long, open: suspend () -> NfcTransceiver?) {
        // One connection at a time: a callback storm must not open several.
        if (!lock.tryLock()) return
        var socket: NfcTransceiver? = null
        try {
            val opened = open() ?: return
            socket = opened
            connection = opened
            if (!isCurrent(attemptId)) return
            // Emitted on a real reader connection, never on discovery.
            events(NfcEvent.Reading(attemptId))

            val bytes = withTimeout(exchangeTimeoutMs) { readInvitation(attemptId, opened) }
            if (!isCurrent(attemptId)) return
            val uri = runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }
                .getOrElse {
                    events(NfcEvent.ReadFailed(attemptId, PairingFailure.InvalidInvitation))
                    return
                }
            // The complete invitation is validated before any network work.
            if (validate(uri).isFailure) {
                events(NfcEvent.ReadFailed(attemptId, PairingFailure.InvalidInvitation))
                return
            }
            events(NfcEvent.Invitation(attemptId, uri))
        } catch (timeout: TimeoutCancellationException) {
            report(attemptId, PairingFailure.Timeout)
        } catch (cancelled: CancellationException) {
            // A cancelled attempt is not a failure to show: it was replaced.
            throw cancelled
        } catch (status: ApduStatusException) {
            report(attemptId, failureFor(status))
        } catch (failure: Exception) {
            report(attemptId, PairingFailure.Interrupted)
        } finally {
            connection = null
            runCatching { socket?.close() }
            lock.unlock()
        }
    }

    private fun report(attemptId: Long, failure: PairingFailure) {
        if (isCurrent(attemptId)) events(NfcEvent.ReadFailed(attemptId, failure))
    }

    private suspend fun readInvitation(attemptId: Long, socket: NfcTransceiver): ByteArray {
        val selected = command(attemptId, socket, NfcApduProtocol.selectCommand())
        val header = NfcApduProtocol.parseSelectResponse(selected)
        val assembler = NfcInvitationAssembler(header.length)
        var offset = 0
        while (offset < header.length) {
            val count = minOf(NfcApduProtocol.MAX_READ_BYTES, header.length - offset)
            val chunk = NfcApduProtocol.parseReadResponse(
                command(attemptId, socket, NfcApduProtocol.readCommand(offset, count)),
                count,
            )
            // A retransmitted chunk advances nothing; the loop must not spin.
            if (assembler.accept(offset, chunk)) offset += count
        }
        return assembler.bytes()
    }

    private suspend fun command(
        attemptId: Long,
        socket: NfcTransceiver,
        apdu: ByteArray,
    ): ByteArray {
        if (!isCurrent(attemptId)) throw CancellationException("attempt replaced")
        return withTimeout(commandTimeoutMs) { socket.transceive(apdu) }
    }

    private fun failureFor(status: ApduStatusException): PairingFailure = when (status.status) {
        NfcApduProtocol.SW_NO_OFFER -> PairingFailure.OfferExpired
        NfcApduProtocol.SW_WRONG_AID -> PairingFailure.InvalidInvitation
        else -> PairingFailure.Interrupted
    }
}
