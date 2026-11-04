package app.simt.echo.ui.copy

/**
 * Copy for the two pairing surfaces (plan 0005 §2).
 *
 * The plan asked for Compose string resources. This build cannot read them:
 * `DefaultAndroidResourceReader` needs an Android resource context the
 * Robolectric host gate does not initialise, and the Gradle task that
 * mirrors generated compose resources into `androidApp/src/main/assets` is
 * unwired for this AGP/KMP combination (the same orphaned task documented
 * in `EchoFonts.kt`, where the fonts are mirrored by hand instead).
 *
 * So the strings live here, like every other screen's copy, until a slice
 * owns fixing that resource plumbing — copy that no test can read and no
 * device can resolve would be worse than copy in one obvious place.
 */
object PairingCopy {

    const val METHOD_GROUP = "Pairing method"
    const val NFC_LABEL = "NFC · Tap phones"
    const val QR_SHOW_LABEL = "QR · Show code"
    const val QR_SCAN_LABEL = "QR · Scan code"
    const val METHOD_LOCKED = "Finish or cancel this transfer to change the method."
    const val SAME_NETWORK = "Same Wi-Fi or hotspot needed."
    const val MATCHING_MODE = "Ask the other person to choose the same method."

    // Share, NFC selected.
    const val SHARE_READY = "Ready to tap"
    const val SHARE_PREPARING = "Preparing tap…"
    const val SHARE_TAP_READ = "Tap details shared"
    const val SHARE_TAP_INSTRUCTION =
        "Ask the other person to open Receive → NFC. Hold the backs of your unlocked phones close together."
    const val SHARE_BY_QR = "This phone can share by QR"
    const val SHARE_WAITING = "Waiting for the other phone…"
    const val SHARE_STARTING = "Starting offer…"
    const val SHARE_DOWNLOADED = "Route downloaded"
    const val SHARE_AS_FILE = "Share as file"
    const val SHARE_TRY_AGAIN = "Try again"
    const val SHARE_AGAIN = "Share again"

    // Receive.
    const val RECEIVE_READY = "Ready to receive a tap"
    const val RECEIVE_PREPARING = "Preparing to receive…"
    const val RECEIVE_TAP_INSTRUCTION =
        "Ask the other person to open Share → NFC. Move the backs of the phones slowly until they connect."
    const val RECEIVE_SCAN_PROMPT = "Scan the other phone’s code"
    const val RECEIVE_CHOOSE_NFC = "Choose NFC to receive by tap."
    const val RECEIVE_FETCHING = "Fetching…"
    const val RECEIVE_IMPORT_FILE = "Import file"
    const val RECEIVE_SAVE = "Save"
    const val RECEIVE_CANCEL = "Cancel"
    const val RECEIVE_SCAN_AGAIN = "Scan again"

    // Recovery and typed failures.
    const val TURN_ON_NFC = "Turn on NFC to tap"
    const val OPEN_NFC_SETTINGS = "Open NFC settings"
    const val USE_QR_INSTEAD = "Use QR instead"
    const val NFC_UNAVAILABLE = "NFC isn’t available on this phone."
    const val TAP_INTERRUPTED = "Tap interrupted. Hold the phones together and try again."
    const val NOT_AN_ECHO_SHARE = "This isn’t an ECHO share."
    const val NO_TAP_YET = "No tap yet. Keep both phones unlocked, or use QR."
    const val TAP_TIMED_OUT = "Tap timed out"
    const val TRY_TAP_AGAIN = "Try tap again"
    const val TAP_NETWORK_FAILED =
        "Tap connected, but the route couldn’t transfer. Join the same Wi-Fi or hotspot and try again."
    const val UNVERIFIED = "Couldn’t verify this route."
    const val OFFER_EXPIRED = "That invitation expired. Ask for a fresh one."
    const val SOMETHING_WRONG = "Something interrupted the tap. Try again."
}
