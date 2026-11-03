package app.simt.echo.transfer

/**
 * Every way an NFC pairing step can fail, as a typed value (plan 0005
 * §4.1). The UI maps these to resource copy; no screen ever renders
 * `exception.message`, an APDU status, or any part of an invitation URI.
 *
 * Each case is deliberately distinct because the recovery differs: a
 * [Disabled] adapter is a settings trip, [Timeout] is "try the tap again",
 * [Interrupted] is "hold still", and [Verification] must never be turned
 * into network advice.
 */
sealed class PairingFailure {

    /** This phone cannot do this role's NFC exchange at all (no radio, no HCE, iOS). */
    data object Unsupported : PairingFailure()

    /** The NFC radio exists but the adapter is off. */
    data object Disabled : PairingFailure()

    /** The connection broke mid-exchange; retryable in the same method. */
    data object Interrupted : PairingFailure()

    /** No invitation arrived in the bounded polling window. */
    data object Timeout : PairingFailure()

    /** Bytes arrived but are not a valid ECHO invitation. */
    data object InvalidInvitation : PairingFailure()

    /** The published offer expired or was replaced before it was read. */
    data object OfferExpired : PairingFailure()

    /** The invitation was valid; the transfer it describes failed to reach the peer. */
    data object Network : PairingFailure()

    /** The bundle arrived but failed its fingerprint or decryption check. */
    data object Verification : PairingFailure()

    /** Anything else. Carries no message: exception text can quote the URI. */
    data object Internal : PairingFailure()

    override fun toString(): String = this::class.simpleName ?: "PairingFailure"
}
