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
import android.nfc.cardemulation.CardEmulation
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground NFC presentation on Android (plan 0005 §4.4).
 *
 * Publishing means: write the immutable snapshot into the process
 * registry, select this app's HCE service as the *foreground* preferred
 * service, and only then report ready. If the platform refuses the
 * selection the snapshot is withdrawn and the UI gets a typed failure —
 * "Ready to tap" is never rendered for a request that did not take.
 *
 * The default wallet role is never requested: [CardEmulation.setPreferredService]
 * is a foreground override that lapses with the activity, which is exactly
 * the "present only while Share is resumed" rule.
 *
 * The reader side lands in W20-05; until then [startReading] is an explicit
 * no-op rather than a pretend success.
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

    private var busJob: Job? = null
    private var receiver: BroadcastReceiver? = null

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
        // W20-05 owns the reader. Reporting a read attempt here would be a
        // fake state; the screen shows NFC receive as unsupported until then.
    }

    override fun stopReading(attemptId: Long) = Unit

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

    /** Leaving the foreground stops presenting, immediately. */
    fun onPaused() {
        ActiveNfcOfferRegistry.disarm()
        unselectForegroundService()
        _capabilities.value = readCapabilities()
        val current = _publication.value
        if (current.ready) {
            _publication.value = NfcPublication(generation = current.generation, ready = false)
        }
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

/** The hosting Activity, or null when composed outside one (previews, tests). */
private fun Context.findActivity(): Activity? {
    var candidate: Context? = this
    while (candidate != null) {
        if (candidate is Activity) return candidate
        candidate = (candidate as? ContextWrapper)?.baseContext
    }
    return null
}
