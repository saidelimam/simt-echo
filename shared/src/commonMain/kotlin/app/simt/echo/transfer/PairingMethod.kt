package app.simt.echo.transfer

import app.simt.echo.platform.NfcCapabilities

/**
 * Which handshake the user picked for this visit (plan 0005 §1).
 *
 * Both destinations open in [NFC]: the tap is the headline exchange, and a
 * disarmed reader is indistinguishable from a dead radio — the private AID
 * gives the OS nothing to react to when the phones meet — so the side that
 * taps must be armed by default. Those are per-visit defaults, never
 * remembered preferences: re-entering either destination restores the
 * default. File actions are not methods — they stay secondary actions
 * beside whichever method is selected.
 */
enum class PairingMethod {
    NFC,
    QR,
}

/** The two destinations that hand a route from one phone to another. */
enum class PairingRole {
    SHARE,
    RECEIVE,
}

/**
 * Where an accepted invitation came from. Attribution only — both sources
 * feed the same bounded parser and the same single-flight receive attempt,
 * so a bug cannot be "fixed" by branching on the origin.
 */
enum class PairingSource {
    QR,
    NFC,
}

/**
 * How far native NFC acquisition has got for the selected method. Distinct
 * from hardware capability: a phone with HCE can still be [IDLE] because
 * the user chose QR, and asking for publication is [PREPARING] until the
 * platform confirms it — never "Ready".
 */
enum class PairingPhase {
    IDLE,
    PREPARING,
    ARMED,
}

/**
 * What the selector may claim about NFC right now.
 *
 * [UNAVAILABLE] means this phone cannot do the role (no NFC radio, no HCE
 * for sending, or iOS). [DISABLED] means the hardware exists but the
 * adapter is off — the user can turn it on. [PREPARING] means the platform
 * was asked and has not confirmed. Only [ARMED] is honest to render as
 * "Ready to tap".
 */
enum class NfcReadiness {
    UNAVAILABLE,
    DISABLED,
    PREPARING,
    ARMED,
}

/**
 * Per-role NFC policy (plan 0005 §4.1).
 *
 * Pure so every default and capability mapping is host-tested without a
 * radio: the platform controller only reports what the hardware says, and
 * decides nothing about what a role may do with it.
 */
object PairingPolicy {

    /** The method a destination opens in, on every new visit. */
    fun defaultMethod(role: PairingRole): PairingMethod = when (role) {
        PairingRole.SHARE -> PairingMethod.NFC
        PairingRole.RECEIVE -> PairingMethod.NFC
    }

    /**
     * Whether this role's NFC exchange is physically possible here.
     *
     * Sending needs the HCE component as well as the radio; receiving needs
     * only the reader. A phone that can read but not emulate keeps NFC
     * receive — the plan never asks receive to depend on sending.
     */
    fun supportedBy(role: PairingRole, capabilities: NfcCapabilities): Boolean = when (role) {
        PairingRole.SHARE -> capabilities.canPresent
        PairingRole.RECEIVE -> capabilities.canRead
    }

    /** Whether the role's NFC exchange can run right now (hardware and adapter on). */
    fun availableNow(role: PairingRole, capabilities: NfcCapabilities): Boolean =
        supportedBy(role, capabilities) && capabilities.enabled

    /**
     * The honest readiness label for NFC in [role]. [phase] is the caller's
     * own acquisition state; capability wins, so a phone that cannot do
     * this role never renders "preparing" or "ready".
     */
    fun readiness(
        role: PairingRole,
        capabilities: NfcCapabilities,
        phase: PairingPhase,
    ): NfcReadiness = when {
        !supportedBy(role, capabilities) -> NfcReadiness.UNAVAILABLE
        !capabilities.enabled -> NfcReadiness.DISABLED
        phase == PairingPhase.ARMED -> NfcReadiness.ARMED
        else -> NfcReadiness.PREPARING
    }

    /**
     * Whether choosing [method] is an action that can do anything, i.e. the
     * selector may switch to it without an immediate dead end. QR is always
     * selectable: the camera gate explains a denial itself.
     */
    fun selectable(role: PairingRole, method: PairingMethod, capabilities: NfcCapabilities): Boolean =
        method == PairingMethod.QR || supportedBy(role, capabilities)
}
