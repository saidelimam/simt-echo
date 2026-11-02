package app.simt.echo.transfer

import app.simt.echo.capture.FaceDetector
import app.simt.echo.capture.GlimpseEntry
import app.simt.echo.capture.excludeFaces
import app.simt.echo.data.DraftRepository
import app.simt.echo.media.sha256Hex
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/**
 * Ready-on-arrival consent (plan W11-09): the Record pre-start overlay
 * offers "Find landmarks while I walk". Default off — no byte leaves the
 * phone until the consent sheet is accepted. [wifiOnly] is the default
 * policy; the Settings switch "Use mobile data" turns it off, never the
 * consent sheet, so the data decision is made once and visibly.
 */
data class ReadyUploadConsent(
    val enabled: Boolean = false,
    val wifiOnly: Boolean = true,
) {
    /**
     * Whether a walk-time upload may use a network of this [kind]. Offline
     * and roaming never upload, even with mobile data allowed (roaming is
     * the one case where data costs the user real money by surprise).
     */
    fun allows(kind: NetworkKind): Boolean = when (kind) {
        NetworkKind.UNMETERED -> true
        NetworkKind.METERED -> !wifiOnly
        NetworkKind.ROAMING, NetworkKind.OFFLINE -> false
    }

    companion object {
        /** Consent granted at the pre-start sheet, under the Settings data policy. */
        fun granted(mobileDataAllowed: Boolean) =
            ReadyUploadConsent(enabled = true, wifiOnly = !mobileDataAllowed)
    }
}

/** What the active network is, for the walk uploader's data policy. */
enum class NetworkKind {
    /** Wi-Fi or any network the system reports as not metered. */
    UNMETERED,

    /** A metered network, typically mobile data. */
    METERED,

    /** Mobile data while roaming. */
    ROAMING,

    /** No usable internet. Unknown also lands here: fail closed. */
    OFFLINE,
}

/**
 * Network seam (plan W11-09). Production wires `ConnectivityManager`; tests
 * script a fake. Anything unknown is [NetworkKind.OFFLINE].
 */
fun interface NetworkGate {
    fun kind(): NetworkKind
}

/** Fail-closed gate for platforms with no verified network view (iOS in v1). */
val OfflineNetworkGate = NetworkGate { NetworkKind.OFFLINE }

/**
 * Maps the three system capabilities to a [NetworkKind]. A network that is
 * not validated internet is [NetworkKind.OFFLINE]; otherwise not-metered wins,
 * then roaming, then plain metered.
 */
fun networkKindOf(hasInternet: Boolean, notMetered: Boolean, notRoaming: Boolean): NetworkKind = when {
    !hasInternet -> NetworkKind.OFFLINE
    notMetered -> NetworkKind.UNMETERED
    !notRoaming -> NetworkKind.ROAMING
    else -> NetworkKind.METERED
}

/** Outcome of one closed checkpoint window (plan W11-09). */
enum class ReadyStageOutcome {
    /** Privacy-filtered strip staged with the server. */
    Staged,

    /** No consent: nothing touched — no draft write, no network call. */
    SkippedNoConsent,

    /** The consent's data policy refuses this network (metered under Wi-Fi only, or roaming): nothing sent. */
    SkippedMetered,

    /** No usable network: nothing sent, nothing staged. */
    SkippedOffline,

    /** The window held no sendable bytes after filtering. */
    SkippedEmpty,

    /**
     * No [FaceDetector] is configured: the consent sheet promises
     * face-filtered strips, so nothing is staged unfiltered.
     */
    SkippedNoFilter,
}

/**
 * Staged walk-upload sessions (`ready-upload.json`, plan W11-09): one
 * upload id per closed checkpoint window, so a restart still discards
 * (or finishes against) every staged blob. Cleared on job create and
 * on discard.
 */
@Serializable
data class ReadyStagedRecord(
    val uploadIds: List<String>,
    val updatedAtMs: Long,
)

/**
 * Walk-time strip staging (plan W11-09): as each checkpoint window
 * closes, its strip uploads — privacy filter first — against a
 * per-window upload session. Finish creates the job against the
 * already-uploaded blobs ([finish]); cancel or discard deletes the
 * staged uploads ([discard]).
 *
 * Load-bearing order, enforced in the code path and pinned by
 * `ReadyUploadTest`: consent → network policy → privacy filter →
 * network. With consent off (the default), offline, roaming, on a
 * metered network under Wi-Fi-only consent, or with no [faceDetector]
 * (the consent copy promises face-filtered strips), no call reaches
 * the API.
 *
 * Blob ids are the ledger JPEG file names; the Finish manifest built
 * from the same ledger maps 1:1. Owed: the live motion→window trigger
 * and Finish-manifest coordination (no in-walk ledger capture exists
 * yet), plus device evidence (result ready ≤ 15 s after Finish on a
 * 60 m walk).
 */
class ReadyUploader(
    private val api: V11CloudApi,
    private val network: NetworkGate,
    private val drafts: DraftRepository,
    private val draftId: String,
    private val faceDetector: FaceDetector? = null,
    private val nowMs: () -> Long = { 0L },
) {
    /** Default off: set only via the pre-start consent sheet. */
    var consent: ReadyUploadConsent = ReadyUploadConsent()
        private set

    /** Records the pre-start consent-sheet decision. */
    fun grantConsent(consent: ReadyUploadConsent = ReadyUploadConsent(enabled = true)) {
        this.consent = consent
    }

    /** Revokes consent: staged blobs stay until [discard] deletes them. */
    fun revokeConsent() {
        consent = consent.copy(enabled = false)
    }

    /**
     * Stages one closed checkpoint window: declares its privacy-filtered
     * strip and PUTs the missing bytes. Skips (never throws) on the
     * consent, metering and filter gates; an empty post-filter window
     * stages nothing. The upload id is recorded as soon as the session
     * exists, before any PUT, so a crash mid-window still leaves
     * [discard] a session to delete.
     */
    suspend fun stageWindow(
        entries: List<GlimpseEntry>,
        readBytes: (GlimpseEntry) -> ByteArray?,
    ): ReadyStageOutcome {
        if (!consent.enabled) return ReadyStageOutcome.SkippedNoConsent
        val kind = network.kind()
        if (kind == NetworkKind.OFFLINE) return ReadyStageOutcome.SkippedOffline
        if (!consent.allows(kind)) return ReadyStageOutcome.SkippedMetered
        if (faceDetector == null) return ReadyStageOutcome.SkippedNoFilter
        val sendable = excludeFaces(entries, faceDetector)
        val files = sendable.mapNotNull { entry ->
            val name = entry.jpegPath?.substringAfterLast('/')?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val bytes = readBytes(entry) ?: return@mapNotNull null
            UploadFile(name, "image/jpeg", bytes)
        }
        if (files.isEmpty()) return ReadyStageOutcome.SkippedEmpty
        val refs = files.map { file -> UploadFileRef(file.id, sha256Hex(file.bytes), file.bytes.size.toLong()) }
        val created = api.createUpload(refs)
        val staged = drafts.loadReadyStagedRecord(draftId)
        drafts.saveReadyStagedRecord(
            draftId,
            ReadyStagedRecord(staged.orEmpty() + created.uploadId, nowMs()),
        )
        val byId = files.associateBy { it.id }
        created.missing.forEach { id -> byId[id]?.let { api.putUploadFile(created.uploadId, it) } }
        return ReadyStageOutcome.Staged
    }

    /**
     * Creates the job against the already-uploaded blobs: no PUT, only
     * the multi-session create. Fail-closed on missing consent or with
     * nothing staged.
     */
    suspend fun finish(manifestJson: String, idempotencyKey: String): String {
        if (!consent.enabled) throw EchoApiError.ConsentRequired()
        val staged = drafts.loadReadyStagedRecord(draftId)
        if (staged.isNullOrEmpty()) throw IllegalStateException("finish: no staged windows")
        val jobId = api.createJobFromStaged(manifestJson, staged, idempotencyKey)
        drafts.clearReadyStagedRecord(draftId)
        return jobId
    }

    /**
     * Deletes every staged upload server-side, then clears the record.
     * Mirrors `deleteNow` discipline: a failed delete keeps the record
     * (orphans fall to the server's staging-TTL sweep) and rethrows.
     */
    suspend fun discard() {
        val staged = drafts.loadReadyStagedRecord(draftId) ?: return
        var failure: Exception? = null
        for (uploadId in staged) {
            try {
                api.deleteUpload(uploadId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (failure == null) failure = e
            }
        }
        if (failure != null) throw failure
        drafts.clearReadyStagedRecord(draftId)
    }
}
