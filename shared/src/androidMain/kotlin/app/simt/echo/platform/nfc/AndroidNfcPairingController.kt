package app.simt.echo.platform.nfc

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.cardemulation.CardEmulation
import android.nfc.tech.IsoDep
import android.os.SystemClock
import androidx.core.content.ContextCompat
import app.simt.echo.platform.NfcCapabilities
import app.simt.echo.platform.NfcEvent
import app.simt.echo.platform.NfcPairingController
import app.simt.echo.platform.NfcPublication
import app.simt.echo.transfer.NfcApduProtocol
import app.simt.echo.transfer.NfcOfferSnapshot
import app.simt.echo.transfer.OFFER_LIFETIME_MS
import app.simt.echo.transfer.PairingFailure
import app.simt.echo.transfer.secureRandomBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * Foreground NFC pairing on Android (plan 0005 §4.4).
 *
 * Presenting means: write the immutable snapshot into the process
 * registry, select this app's HCE service as the *foreground* preferred
 * service, and only then report ready. If the platform refuses the
 * selection the snapshot is withdrawn and the UI gets a typed failure —
 * "Ready to tap" is never rendered for a request that did not take.
 *
 * Reading arms reader mode with NDEF checking off (this is a raw APDU
 * exchange) and the platform sounds off (ECHO supplies its own haptic).
 * Reader mode disables local card emulation, so the two roles never run
 * together: the screen stops the QR scanner before arming this, and the
 * controller never arms both sides itself.
 */
class AndroidNfcPairingController private constructor(
    private val context: Context,
    private val activity: Activity?,
    private val adapter: NfcAdapter?,
    private val hasHce: Boolean,
) : NfcPairingController {

    private val _capabilities = MutableStateFlow(readCapabilities())
    private val _publication = MutableStateFlow(NfcPublication.IDLE)
    private val _events = MutableSharedFlow<NfcEvent>(extraBufferCapacity = 16)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val reader = NfcInvitationReader(
        scope = scope,
        events = { event ->
            _events.tryEmit(event)
            val attempt = readingAttempt
            when (event) {
                // A complete invitation ends acquisition; the screen takes
                // over with fetching, and must not keep polling.
                is NfcEvent.Invitation -> if (attempt != null) scope.launch { stopReading(attempt) }

                // A retryable failure leaves reader mode armed: the same tap
                // can simply be repeated, so the 20 s window restarts rather
                // than cutting the user off on the old clock (R14-04).
                is NfcEvent.ReadFailed ->
                    if (attempt != null && event.attemptId == attempt && event.failure.keepsListening) {
                        armIdleTimeout(attempt)
                    }

                else -> Unit
            }
        },
    )

    private var busJob: Job? = null
    private var idleJob: Job? = null
    private var receiver: BroadcastReceiver? = null
    private var readingAttempt: Long? = null

    /** The invitation the screen asked for, so resume can re-arm it once. */
    private var requested: Pair<String, Long>? = null

    override val capabilities: StateFlow<NfcCapabilities> = _capabilities.asStateFlow()
    override val publication: StateFlow<NfcPublication> = _publication.asStateFlow()
    override val events: Flow<NfcEvent> = _events

    override fun publish(invitation: String, offerGeneration: Long) {
        requested = invitation to offerGeneration
        val blocked = presentationFailure()
        if (blocked != null) {
            failPublication(offerGeneration, blocked)
            return
        }
        ActiveNfcOfferRegistry.publish(
            NfcOfferSnapshot(
                generation = offerGeneration,
                offerId = secureRandomBytes(NfcApduProtocol.OFFER_ID_BYTES),
                invitation = invitation.encodeToByteArray(),
                expiresAtMonotonicMs = SystemClock.elapsedRealtime() + OFFER_LIFETIME_MS,
            ),
        )
        if (!selectForegroundService()) {
            // A registration the platform refused must never look ready.
            failPublication(offerGeneration, PairingFailure.Unsupported)
            return
        }
        ActiveNfcOfferRegistry.arm()
        _publication.value = NfcPublication(generation = offerGeneration, ready = true)
        _events.tryEmit(NfcEvent.Published(offerGeneration))
    }

    override fun startReading(attemptId: Long) {
        val activity = activity ?: run {
            failRead(attemptId, PairingFailure.Unsupported)
            return
        }
        val adapter = adapter ?: run {
            failRead(attemptId, PairingFailure.Unsupported)
            return
        }
        if (!adapter.isEnabled) {
            failRead(attemptId, PairingFailure.Disabled)
            return
        }
        readingAttempt = attemptId
        // NDEF checking off: ECHO speaks APDUs. Platform sounds off: the
        // screen supplies one accessible haptic per real connection.
        val flags = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
            NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
        // Armed before reader mode is enabled: a tag that arrives on the
        // first callback already belongs to this attempt.
        reader.arm(attemptId)
        if (!enableReaderMode(adapter, activity, flags, attemptId)) {
            readingAttempt = null
            reader.stopReading(attemptId)
            failRead(attemptId, PairingFailure.Unsupported)
            return
        }
        armIdleTimeout(attemptId)
    }

    /** One idle window; retryable failures restart it for the same attempt. */
    private fun armIdleTimeout(attemptId: Long) {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(NFC_READER_IDLE_TIMEOUT_MS.milliseconds)
            if (readingAttempt == attemptId) {
                // No automatic repeat: the screen offers "Try tap again".
                stopReading(attemptId)
                _events.tryEmit(NfcEvent.ReadFailed(attemptId, PairingFailure.Timeout))
            }
        }
    }

    override fun stopReading(attemptId: Long) {
        if (readingAttempt != attemptId) return
        readingAttempt = null
        idleJob?.cancel()
        idleJob = null
        reader.stopReading(attemptId)
        disableReaderMode()
    }

    override fun clearPublishedOffer(offerGeneration: Long) {
        if (requested?.second != offerGeneration) {
            // A stale clear must not unpublish a newer offer.
            return
        }
        requested = null
        unselectForegroundService()
        ActiveNfcOfferRegistry.clear(offerGeneration)
        _publication.value = NfcPublication.IDLE
    }

    override fun close() {
        readingAttempt?.let { attempt ->
            reader.stopReading(attempt)
            readingAttempt = null
        }
        idleJob?.cancel()
        idleJob = null
        disableReaderMode()
        requested = null
        unselectForegroundService()
        ActiveNfcOfferRegistry.clearAll()
        _publication.value = NfcPublication.IDLE
        busJob?.cancel()
        busJob = null
        receiver?.let { registered ->
            runCatching { context.unregisterReceiver(registered) }
        }
        receiver = null
        reader.close()
        scope.cancel()
    }

    /** Registers the adapter-state listener; safe to call more than once. */
    fun start() {
        if (receiver != null) return
        val listener = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                _capabilities.value = readCapabilities()
                // Turning NFC back on re-arms the offer the screen still has.
                if (_capabilities.value.enabled) onResumed()
            }
        }
        runCatching {
            ContextCompat.registerReceiver(
                context,
                listener,
                IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
        receiver = listener
        busJob = scope.launch {
            NfcEventBus.events.collect { event -> _events.emit(event) }
        }
    }

    /** The destination is foreground: capabilities and publication re-check. */
    fun onResumed() {
        _capabilities.value = readCapabilities()
        val (invitation, generation) = requested ?: return
        publish(invitation, generation)
    }

    /** Leaving the foreground stops both roles, immediately. */
    fun onPaused() {
        readingAttempt?.let { attempt ->
            reader.stopReading(attempt)
            readingAttempt = null
        }
        idleJob?.cancel()
        idleJob = null
        disableReaderMode()
        ActiveNfcOfferRegistry.disarm()
        unselectForegroundService()
        _capabilities.value = readCapabilities()
        val current = _publication.value
        if (current.ready) {
            _publication.value = NfcPublication(generation = current.generation, ready = false)
        }
    }

    private fun enableReaderMode(
        adapter: NfcAdapter,
        activity: Activity,
        flags: Int,
        attemptId: Long,
    ): Boolean = runCatching {
        adapter.enableReaderMode(
            activity,
            { tag -> onTagDiscovered(attemptId, tag) },
            flags,
            null,
        )
    }.isSuccess

    private fun disableReaderMode() {
        val adapter = adapter ?: return
        val activity = activity ?: return
        runCatching { adapter.disableReaderMode(activity) }
    }

    /**
     * A tag arrived. A callback for an attempt that has been replaced is
     * dropped here, so a stale tap can never feed a newer attempt.
     */
    private fun onTagDiscovered(attemptId: Long, tag: Tag) {
        if (readingAttempt != attemptId) return
        reader.start(attemptId) {
            IsoDep.get(tag)?.let { isoDep -> IsoDepTransceiver(isoDep) }
        }
    }

    private fun failRead(attemptId: Long, failure: PairingFailure) {
        _events.tryEmit(NfcEvent.ReadFailed(attemptId, failure))
    }

    private fun presentationFailure(): PairingFailure? {
        val adapter = adapter ?: return PairingFailure.Unsupported
        if (!hasHce) return PairingFailure.Unsupported
        if (!adapter.isEnabled) return PairingFailure.Disabled
        if (activity == null) return PairingFailure.Unsupported
        return null
    }

    private fun failPublication(generation: Long, failure: PairingFailure) {
        ActiveNfcOfferRegistry.clearAll()
        _publication.value = NfcPublication(generation = generation, ready = false, failure = failure)
        _events.tryEmit(NfcEvent.PublishFailed(generation, failure))
    }

    private fun selectForegroundService(): Boolean {
        val activity = activity ?: return false
        val adapter = adapter ?: return false
        val service = ComponentName(context, EchoHostApduService::class.java)
        return runCatching {
            CardEmulation.getInstance(adapter).setPreferredService(activity, service)
        }.getOrDefault(false)
    }

    private fun unselectForegroundService() {
        val adapter = adapter ?: return
        val activity = activity ?: return
        runCatching { CardEmulation.getInstance(adapter).unsetPreferredService(activity) }
    }

    private fun readCapabilities(): NfcCapabilities {
        val adapter = adapter ?: return NfcCapabilities.NONE
        return NfcCapabilities(
            canRead = true,
            canPresent = hasHce,
            enabled = adapter.isEnabled,
        )
    }

    companion object {
        fun create(context: Context): AndroidNfcPairingController {
            val appContext = context.applicationContext
            val adapter = runCatching { NfcAdapter.getDefaultAdapter(appContext) }.getOrNull()
            val hasHce = runCatching {
                appContext.packageManager.hasSystemFeature(
                    PackageManager.FEATURE_NFC_HOST_CARD_EMULATION,
                )
            }.getOrDefault(false)
            return AndroidNfcPairingController(
                context = appContext,
                activity = context.findActivity(),
                adapter = adapter,
                hasHce = hasHce,
            )
        }
    }
}

/**
 * The production transceiver: `IsoDep` over the discovered tag, with the
 * per-command timeout the protocol specifies. Connect, transceive and close
 * all happen off the UI thread.
 */
private class IsoDepTransceiver(private val isoDep: IsoDep) : NfcTransceiver {

    override suspend fun transceive(command: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        if (!isoDep.isConnected) isoDep.connect()
        isoDep.timeout = NFC_COMMAND_TIMEOUT_MS.toInt()
        isoDep.transceive(command)
    }

    /** Interrupts a blocked transceive; safe to call more than once. */
    override fun close() {
        runCatching { isoDep.close() }
    }
}

/** The hosting Activity, or null when composed outside one (previews, tests). */
private fun Context.findActivity(): Activity? {
    var candidate: Context? = this
    while (candidate != null) {
        if (candidate is Activity) return candidate
        candidate = (candidate as? ContextWrapper)?.baseContext
    }
    return null
}
