package app.simt.echo.transfer

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

/** Outcome of staging one batch of closed-checkpoint files. */
enum class ReadyStageOutcome {
    /** The batch is staged with the server. */
    Staged,

    /** No consent: nothing touched — no draft write, no network call. */
    SkippedNoConsent,

    /** The consent's data policy refuses this network (metered under Wi-Fi only, or roaming): nothing sent. */
    SkippedMetered,

    /** No usable network: nothing sent, nothing staged. */
    SkippedOffline,

    /** Every file in the batch is already staged byte for byte, or the batch is empty. */
    SkippedEmpty,

    /** [MAX_STAGED_SESSIONS] sessions are staged: the rest ships with the Finish upload. */
    SkippedFull,
}

/**
 * Staged walk-upload sessions (`ready-upload.json`, plan W11-09): one
 * upload id per staged batch, so a restart still discards (or finishes
 * against) every staged blob. Cleared on job create and on discard.
 *
 * [files] maps each staged file name to the SHA-256 of the bytes staged under
 * it. Finish compares its own bytes against it, so a file Review changed since
 * the walk (a recaptured keyframe) is sent again instead of silently shipping
 * the stale one.
 */
@Serializable
data class ReadyStagedRecord(
    val uploadIds: List<String>,
    val updatedAtMs: Long,
    val files: Map<String, String> = emptyMap(),
)

/**
 * The worker takes at most 12 upload sessions per job; one is kept for what
 * Finish still has to send, so a walk stages at most this many.
 */
const val MAX_STAGED_SESSIONS = 11

/**
 * Walk-time staging (plan W11-09): as checkpoints close, their files — built
 * by [UploadPreparer], privacy filter included — upload into one session per
 * batch. Finish then creates the job against what is already there
 * ([createJobFromWalkStaging]); cancel or discard deletes it ([discard]).
 *
 * This class owns the policy, not the content: consent → network → bytes, in
 * that order, pinned by `ReadyUploadTest`. With consent off (the default),
 * offline, roaming, or on a metered network under Wi-Fi-only consent, no call
 * reaches the API and nothing is written to the draft. Whether a file is
 * face-filtered is decided upstream by [UploadPreparer]; the walk-time caller
 * refuses to build files at all without a detector, because the consent copy
 * promises filtered strips.
 */
class ReadyUploader(
    private val api: V11CloudApi,
    private val network: NetworkGate,
    private val drafts: DraftRepository,
    private val draftId: String,
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
     * Stages [files]: declares them and PUTs the bytes the server lacks. Skips
     * (never throws) on the consent, network and session-cap gates; files
     * already staged with the same bytes are not sent twice. The session id is
     * recorded as soon as it exists, before any PUT, so a crash mid-batch still
     * leaves [discard] a session to delete.
     */
    suspend fun stage(files: List<UploadFile>): ReadyStageOutcome {
        if (!consent.enabled) return ReadyStageOutcome.SkippedNoConsent
        val kind = network.kind()
        if (kind == NetworkKind.OFFLINE) return ReadyStageOutcome.SkippedOffline
        if (!consent.allows(kind)) return ReadyStageOutcome.SkippedMetered
        val staged = drafts.loadReadyStagedRecord(draftId)
        val hashed = files.map { it to sha256Hex(it.bytes) }
        val fresh = hashed.filter { (file, sha) -> staged?.files?.get(file.id) != sha }
        if (fresh.isEmpty()) return ReadyStageOutcome.SkippedEmpty
        if ((staged?.uploadIds?.size ?: 0) >= MAX_STAGED_SESSIONS) return ReadyStageOutcome.SkippedFull
        val created = api.createUpload(fresh.map { (file, sha) -> UploadFileRef(file.id, sha, file.bytes.size.toLong()) })
        drafts.saveReadyStagedRecord(
            draftId,
            ReadyStagedRecord(
                uploadIds = staged?.uploadIds.orEmpty() + created.uploadId,
                updatedAtMs = nowMs(),
                files = staged?.files.orEmpty() + fresh.associate { (file, sha) -> file.id to sha },
            ),
        )
        val byId = fresh.associate { (file, _) -> file.id to file }
        created.missing.forEach { id -> byId[id]?.let { api.putUploadFile(created.uploadId, it) } }
        return ReadyStageOutcome.Staged
    }

    /**
     * Deletes every staged upload server-side, then clears the record.
     * Mirrors `deleteNow` discipline: a failed delete keeps the record
     * (orphans fall to the server's staging-TTL sweep) and rethrows.
     */
    suspend fun discard() = discardWalkStaging(api, drafts, draftId)
}

/** Deletes the staged sessions of [draftId], keeping the record when a delete fails (see [ReadyUploader.discard]). */
suspend fun discardWalkStaging(api: V11CloudApi, drafts: DraftRepository, draftId: String) {
    val staged = drafts.loadReadyStagedRecord(draftId) ?: return
    var failure: Exception? = null
    for (uploadId in staged.uploadIds) {
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

/**
 * Creates the Finish job against the sessions staged during the walk, or
 * returns null when none were staged (the caller then uploads everything).
 *
 * [files] are the bytes Finish would upload anyway. Those already staged with
 * identical bytes are not sent again; the rest (checkpoints that closed after
 * the last window, files Review changed) go in one final session, listed
 * first because the worker takes a name from the first session that holds it,
 * so changed bytes win over stale staged ones. The record is cleared once the
 * job exists. A server that no longer has a session throws
 * [EchoApiError.UploadStale]; the record is cleared then too, so the caller
 * can fall back to a full upload without the dead sessions.
 */
suspend fun createJobFromWalkStaging(
    api: V11CloudApi,
    drafts: DraftRepository,
    draftId: String,
    manifestJson: String,
    files: List<UploadFile>,
    idempotencyKey: String,
): String? {
    val staged = drafts.loadReadyStagedRecord(draftId)?.takeIf { it.uploadIds.isNotEmpty() } ?: return null
    val hashed = files.map { it to sha256Hex(it.bytes) }
    val remainder = hashed.filter { (file, sha) -> staged.files[file.id] != sha }
    try {
        var sessions = staged.uploadIds
        if (remainder.isNotEmpty()) {
            val created = api.createUpload(remainder.map { (file, sha) -> UploadFileRef(file.id, sha, file.bytes.size.toLong()) })
            val byId = remainder.associate { (file, _) -> file.id to file }
            created.missing.forEach { id -> byId[id]?.let { api.putUploadFile(created.uploadId, it) } }
            sessions = listOf(created.uploadId) + sessions
        }
        val jobId = api.createJobFromStaged(manifestJson, sessions, idempotencyKey)
        drafts.clearReadyStagedRecord(draftId)
        return jobId
    } catch (e: EchoApiError.UploadStale) {
        drafts.clearReadyStagedRecord(draftId)
        throw e
    }
}
