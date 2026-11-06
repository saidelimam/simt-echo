package app.simt.echo.transfer

/**
 * ECHO Tap v1: the private APDU exchange between one phone's foreground
 * card emulation and the other phone's foreground reader (plan 0005 §4.3).
 *
 * This is not a Type 4 NDEF filesystem and not a payment application. It is
 * one proprietary application ID read by ECHO's own reader, with no NFC
 * intent filters and no OS URI dispatch, so tapping never launches another
 * app. All values except the invitation bytes are ECHO design choices.
 *
 * Wire contract:
 * ```
 * SELECT  00 A4 04 00 06 F0 45 43 48 4F 01 00
 * SELECT  "ECHO" | 01 | offerId(8) | length(2 BE) | 90 00
 * READ    00 B0 offsetHi offsetLo Le             (Le = 1 … 224, never 0)
 * READ    <exactly Le invitation bytes> 90 00
 * ```
 * Total invitation 1 … 1024 bytes. Every response is checked for status
 * and exact length before anything is copied or allocated.
 */
object NfcApduProtocol {

    /** Proprietary, locally chosen six-byte AID; `category="other"`. */
    val APPLICATION_ID: ByteArray = byteArrayOf(0xF0.toByte(), 0x45, 0x43, 0x48, 0x4F, 0x01)

    /** ASCII "ECHO": the magic that identifies an ECHO offer. */
    val MAGIC: ByteArray = byteArrayOf(0x45, 0x43, 0x48, 0x4F)

    const val VERSION: Int = 0x01
    const val OFFER_ID_BYTES: Int = 8
    const val LENGTH_BYTES: Int = 2
    const val MIN_INVITATION_BYTES: Int = 1
    const val MAX_INVITATION_BYTES: Int = PairingInvitation.MAX_UTF8_BYTES

    /** Largest chunk a reader may ask for: `Le` is a short APDU value. */
    const val MAX_READ_BYTES: Int = 224

    const val CLA_PROPRIETARY: Int = 0x00
    const val INS_SELECT: Int = 0xA4
    const val INS_READ: Int = 0xB0

    const val SW_OK: Int = 0x9000
    const val SW_WRONG_LENGTH: Int = 0x6700
    const val SW_NO_OFFER: Int = 0x6985
    const val SW_WRONG_AID: Int = 0x6A82
    const val SW_BAD_OFFSET: Int = 0x6B00
    const val SW_UNKNOWN_INS: Int = 0x6D00
    const val SW_UNKNOWN_CLA: Int = 0x6E00

    /** SELECT data length: magic + version + offer id + length. */
    private const val SELECT_DATA_BYTES = 4 + 1 + OFFER_ID_BYTES + LENGTH_BYTES

    // ── reader side: encoders and response parsers ───────────────

    /** The one SELECT command this protocol defines. */
    fun selectCommand(): ByteArray = byteArrayOf(
        CLA_PROPRIETARY.toByte(),
        INS_SELECT.toByte(),
        0x04,
        0x00,
        APPLICATION_ID.size.toByte(),
    ) + APPLICATION_ID + byteArrayOf(0x00)

    /** A READ for [count] bytes at [offset]; both are fixed-width fields. */
    fun readCommand(offset: Int, count: Int): ByteArray {
        require(offset in 0..0xFFFF) { "offset does not fit two bytes" }
        require(count in 1..MAX_READ_BYTES) { "count must be 1…$MAX_READ_BYTES" }
        return byteArrayOf(
            CLA_PROPRIETARY.toByte(),
            INS_READ.toByte(),
            ((offset shr 8) and 0xFF).toByte(),
            (offset and 0xFF).toByte(),
            count.toByte(),
        )
    }

    /**
     * Parses a SELECT response: magic, version, offer id and the declared
     * invitation length. A wrong status is raised as [ApduStatusException]
     * so the caller can map it without reading prose.
     */
    fun parseSelectResponse(response: ByteArray): SelectResponse {
        val sw = statusOf(response)
        if (sw != SW_OK) throw ApduStatusException(sw)
        if (response.size != SELECT_DATA_BYTES + 2) throw ApduStatusException(SW_WRONG_LENGTH)
        val magic = response.copyOfRange(0, 4)
        if (!magic.contentEquals(MAGIC)) throw ApduStatusException(SW_WRONG_AID)
        if (response.u8(4) != VERSION) throw ApduStatusException(SW_WRONG_LENGTH)
        val offerId = response.copyOfRange(5, 5 + OFFER_ID_BYTES)
        val length = response.u16(5 + OFFER_ID_BYTES)
        if (length !in MIN_INVITATION_BYTES..MAX_INVITATION_BYTES) {
            throw ApduStatusException(SW_WRONG_LENGTH)
        }
        return SelectResponse(offerId = offerId, length = length)
    }

    /** Parses a READ response, which must carry exactly [requested] bytes. */
    fun parseReadResponse(response: ByteArray, requested: Int): ByteArray {
        require(requested in 1..MAX_READ_BYTES) { "requested must be 1…$MAX_READ_BYTES" }
        val sw = statusOf(response)
        if (sw != SW_OK) throw ApduStatusException(sw)
        if (response.size != requested + 2) throw ApduStatusException(SW_WRONG_LENGTH)
        return response.copyOfRange(0, requested)
    }

    /** SW1SW2 of any response, or a length error when it is too short. */
    fun statusOf(response: ByteArray): Int {
        if (response.size < 2) throw ApduStatusException(SW_WRONG_LENGTH)
        return response.u16(response.size - 2)
    }

    // ── presenter side: an immutable snapshot and its responder ──
}

/** What a SELECT told the reader before any invitation byte arrived. */
data class SelectResponse(
    val offerId: ByteArray,
    val length: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is SelectResponse &&
            offerId.contentEquals(other.offerId) &&
            length == other.length

    override fun hashCode(): Int = 31 * offerId.contentHashCode() + length

    override fun toString(): String = "SelectResponse(length=$length)"
}

/**
 * A protocol status, which is never secret. The caller maps the status to a
 * typed failure and no message is shown.
 */
class ApduStatusException(val status: Int) : Exception("APDU status ${status.toString(16)}")

/**
 * One immutable published offer as the APDU service sees it (plan 0005
 * §4.4). Arrays are copied in and copied out so no caller can mutate the
 * bytes a transaction already pinned.
 */
class NfcOfferSnapshot(
    val generation: Long,
    offerId: ByteArray,
    invitation: ByteArray,
    val expiresAtMonotonicMs: Long,
) {
    val offerId: ByteArray = offerId.copyOf()
    val invitation: ByteArray = invitation.copyOf()

    val valid: Boolean
        get() = invitation.size in NfcApduProtocol.MIN_INVITATION_BYTES..NfcApduProtocol.MAX_INVITATION_BYTES

    override fun toString(): String =
        "NfcOfferSnapshot(generation=$generation, bytes=${invitation.size})"
}

/**
 * The presenting side of the exchange (plan 0005 §4.3).
 *
 * SELECT pins the current snapshot's generation for this RF transaction;
 * every READ re-checks that the live registry still holds that generation,
 * that the offer has not expired, and that the phone is interactive. A
 * replaced offer therefore invalidates the transaction — bytes from two
 * invitations can never be concatenated.
 *
 * Deactivation clears the selected state and the contiguous-read tracking
 * but never cancels the LAN transfer that follows.
 */
class NfcApduPresenter(
    /** The live publication, or null when nothing is being served. */
    private val snapshot: () -> NfcOfferSnapshot?,
    private val nowMs: () -> Long,
    /** Unlocked and screen on: card emulation is only ever foreground. */
    private val interactive: () -> Boolean,
    /** Fired once per transaction when the whole invitation has been returned. */
    private val onInvitationRead: (Long) -> Unit = {},
) {
    private var selectedGeneration: Long? = null
    private var selectedOfferId: ByteArray? = null
    private var nextOffset: Int = 0
    private var lastOffset: Int = -1
    private var announced: Boolean = false

    fun respond(command: ByteArray): ByteArray {
        if (command.size < 4) return status(NfcApduProtocol.SW_WRONG_LENGTH)
        val cla = command.u8(0)
        if (cla != NfcApduProtocol.CLA_PROPRIETARY) return status(NfcApduProtocol.SW_UNKNOWN_CLA)
        return when (command.u8(1)) {
            NfcApduProtocol.INS_SELECT -> select(command)
            NfcApduProtocol.INS_READ -> read(command)
            else -> status(NfcApduProtocol.SW_UNKNOWN_INS)
        }
    }

    /** Field loss: drop the transaction without touching the offer itself. */
    fun deactivate() {
        selectedGeneration = null
        selectedOfferId = null
        nextOffset = 0
        lastOffset = -1
        announced = false
    }

    private fun select(command: ByteArray): ByteArray {
        val aid = NfcApduProtocol.APPLICATION_ID
        if (command.size != aid.size + 6) return status(NfcApduProtocol.SW_WRONG_LENGTH)
        if (command.u8(2) != 0x04 || command.u8(3) != 0x00) {
            return status(NfcApduProtocol.SW_WRONG_AID)
        }
        if (command.u8(4) != aid.size) return status(NfcApduProtocol.SW_WRONG_LENGTH)
        val named = command.copyOfRange(5, 5 + aid.size)
        if (!named.contentEquals(aid)) return status(NfcApduProtocol.SW_WRONG_AID)
        if (command.u8(5 + aid.size) != 0x00) return status(NfcApduProtocol.SW_WRONG_LENGTH)

        val offer = live() ?: return noOffer()
        selectedGeneration = offer.generation
        selectedOfferId = offer.offerId
        nextOffset = 0
        lastOffset = -1
        announced = false
        val length = offer.invitation.size
        val data = NfcApduProtocol.MAGIC +
            byteArrayOf(NfcApduProtocol.VERSION.toByte()) +
            offer.offerId +
            byteArrayOf(((length shr 8) and 0xFF).toByte(), (length and 0xFF).toByte())
        return data + statusBytes(NfcApduProtocol.SW_OK)
    }

    private fun read(command: ByteArray): ByteArray {
        if (command.size != 5) return status(NfcApduProtocol.SW_WRONG_LENGTH)
        val offset = command.u16(2)
        val count = command.u8(4)
        if (count !in 1..NfcApduProtocol.MAX_READ_BYTES) return status(NfcApduProtocol.SW_BAD_OFFSET)

        val pinned = selectedGeneration ?: return noOffer()
        val offer = live() ?: return noOffer()
        if (offer.generation != pinned) {
            // The registry moved on: this transaction can never mix offers.
            return noOffer()
        }
        val pinnedId = selectedOfferId
        if (pinnedId == null || !offer.offerId.contentEquals(pinnedId)) return noOffer()

        if (offset + count > offer.invitation.size) return status(NfcApduProtocol.SW_BAD_OFFSET)
        // Sequential reads only, but an exact repeat of the last request is
        // a legitimate retry and re-answers with the same bytes.
        if (offset != nextOffset && offset != lastOffset) {
            return status(NfcApduProtocol.SW_BAD_OFFSET)
        }

        val chunk = offer.invitation.copyOfRange(offset, offset + count)
        lastOffset = offset
        nextOffset = offset + count
        if (!announced && nextOffset == offer.invitation.size) {
            announced = true
            // One observation per transaction: a retransmitted final chunk
            // must not look like a second tap.
            onInvitationRead(offer.generation)
        }
        return chunk + statusBytes(NfcApduProtocol.SW_OK)
    }

    /** The live snapshot, or null when it must not be served right now. */
    private fun live(): NfcOfferSnapshot? {
        if (!interactive()) return null
        val offer = snapshot() ?: return null
        if (!offer.valid) return null
        if (offer.expiresAtMonotonicMs <= nowMs()) return null
        return offer
    }

    private fun noOffer(): ByteArray {
        deactivate()
        return status(NfcApduProtocol.SW_NO_OFFER)
    }

    private fun status(status: Int): ByteArray = statusBytes(status)
}

/**
 * Reassembles one invitation from READ responses (plan 0005 §4.3).
 *
 * Chunks must be contiguous; an exact repeat of the previous chunk is a
 * retry and is ignored rather than counted twice. Nothing is returned until
 * the whole invitation is present — a partial invitation is never emitted.
 */
class NfcInvitationAssembler(private val expectedLength: Int) {

    init {
        require(expectedLength in NfcApduProtocol.MIN_INVITATION_BYTES..NfcApduProtocol.MAX_INVITATION_BYTES) {
            "declared length out of bounds"
        }
    }

    private val buffer = ByteArray(expectedLength)
    private var written = 0
    private var lastOffset = -1
    private var lastChunk: ByteArray? = null

    /** @return true when this call advanced the invitation. */
    fun accept(offset: Int, chunk: ByteArray): Boolean {
        require(chunk.isNotEmpty()) { "empty chunk" }
        require(offset >= 0 && offset + chunk.size <= expectedLength) { "chunk out of bounds" }
        if (offset == lastOffset && lastChunk?.contentEquals(chunk) == true) return false
        require(offset == written) { "chunk is not contiguous" }
        chunk.copyInto(buffer, written)
        written += chunk.size
        lastOffset = offset
        lastChunk = chunk.copyOf()
        return true
    }

    val complete: Boolean get() = written == expectedLength

    /** The invitation bytes, valid only once [complete]. */
    fun bytes(): ByteArray {
        require(complete) { "invitation is incomplete" }
        return buffer.copyOf()
    }

    fun invalidate() {
        written = 0
        lastOffset = -1
        lastChunk = null
    }
}

internal fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF

internal fun ByteArray.u16(index: Int): Int = (u8(index) shl 8) or u8(index + 1)

private fun statusBytes(status: Int): ByteArray =
    byteArrayOf(((status shr 8) and 0xFF).toByte(), (status and 0xFF).toByte())
