package app.simt.echo.platform.nfc

import android.app.KeyguardManager
import android.content.Context
import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import app.simt.echo.transfer.NfcApduProtocol
import app.simt.echo.transfer.NfcApduPresenter

/**
 * Foreground card emulation for one ECHO offer (plan 0005 §4.4).
 *
 * The OS binds this only while the app has selected it as the preferred
 * foreground service; it is not the default wallet, and it registers no
 * payment AID and no NFC intent filter, so a tap can never launch another
 * app or move a payment.
 *
 * The callback does three cheap things and nothing else: read the armed
 * snapshot, check the phone is unlocked and awake, and answer from the
 * immutable offer. No disk, no network, no bundle assembly — a blocking
 * APDU handler is how HCE implementations get killed.
 *
 * A cold start has no snapshot (the registry is process state), so a
 * service recreated after a process death answers `6985` rather than
 * serving a stale invitation.
 */
class EchoHostApduService : HostApduService() {

    private var presenter: NfcApduPresenter? = null

    override fun onCreate() {
        super.onCreate()
        presenter = EchoTapPresentation.presenter(
            nowMs = { SystemClock.elapsedRealtime() },
            interactive = ::deviceIsInteractive,
        )
    }

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        val current = presenter ?: return statusOnly(NfcApduProtocol.SW_NO_OFFER)
        return current.respond(commandApdu ?: ByteArray(0))
    }

    override fun onDeactivated(reason: Int) {
        // Field loss: drop this transaction, keep the offer publishable so a
        // fresh SELECT can restart it.
        presenter?.deactivate()
    }

    override fun onDestroy() {
        presenter?.deactivate()
        presenter = null
        // A destroyed service serves nothing: the controller republishes on
        // resume, so there is no window where a stale offer stays visible.
        ActiveNfcOfferRegistry.clearAll()
        super.onDestroy()
    }

    /**
     * Card emulation is only ever foreground (plan 0005 §4.4). Checked
     * independently on every response, because the adapter can stay
     * selected across a lock that happened while the activity was resumed.
     */
    private fun deviceIsInteractive(): Boolean {
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        if (!power.isInteractive) return false
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return true
        return !keyguard.isKeyguardLocked
    }

    private fun statusOnly(status: Int): ByteArray =
        byteArrayOf(((status shr 8) and 0xFF).toByte(), (status and 0xFF).toByte())
}
