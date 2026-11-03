package app.simt.echo.transfer

/**
 * One validated pairing invitation (plan 0005 §4.2).
 *
 * Both transports — camera and radio — accept exactly the same thing, and
 * both must pass through here before any networking starts. NFC carries
 * [bytes], the identical UTF-8 v1 URI a QR encodes; there is no second
 * token, no second key and no schema change.
 *
 * The invitation is a bearer capability. It is formatted as its size only,
 * so it cannot reach a log or a crash report through `toString`, and the
 * failures it produces are typed values, never the parser's message.
 */
class PairingInvitation private constructor(
    val uri: String,
    val payload: QrPayload,
) {

    /** The exact bytes an NFC exchange carries (1 … [MAX_UTF8_BYTES]). */
    val bytes: ByteArray get() = uri.encodeToByteArray()

    /** Never the URI itself: that is the capability. */
    override fun toString(): String = "PairingInvitation(bytes=${bytes.size})"

    companion object {
        /** Hard cap for one invitation, on generation and on parse. */
        const val MAX_UTF8_BYTES = 1024

        /**
         * The single bounded validation entry for QR and NFC alike.
         * Field decoding is delegated to [QrPayload.parse]; this adds the
         * wire bound NFC needs and keeps callers off the raw parser.
         */
        fun parse(uri: String): Result<PairingInvitation> =
            QrPayload.parse(uri).map { payload -> PairingInvitation(uri, payload) }

        fun parseOrThrow(uri: String): PairingInvitation = parse(uri).getOrThrow()

        /**
         * The typed failure a screen can explain. Nothing from the parse
         * error is surfaced: its text is not copy, and it may quote fields.
         */
        fun failureOf(error: Throwable): PairingFailure = PairingFailure.InvalidInvitation

        /** Builds the invitation for an outright payload, enforcing the bound. */
        fun of(payload: QrPayload): PairingInvitation = parseOrThrow(payload.build())
    }
}
