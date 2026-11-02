package app.simt.echo.transfer

import app.simt.echo.capture.GlimpseEntry
import app.simt.echo.capture.liveCheckpoints
import app.simt.echo.data.DraftRepository
import app.simt.echo.media.fillKeyframesFromLedger
import app.simt.echo.model.CaptureLog
import app.simt.echo.model.EchoConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

/**
 * Builds the [WalkStager] for a recording once the user has consented and the
 * draft exists, or null when this build cannot stage honestly (no cloud
 * credentials, no face filter, an API without staged uploads). The Record
 * screen asks only after consent, so a factory is never a way around it.
 */
fun interface WalkStagerFactory {
    fun create(
        draftId: String,
        liveLog: () -> CaptureLog?,
        liveLedger: () -> List<GlimpseEntry>,
    ): WalkStager?
}

/**
 * Upload while walking (plan W11-09): watches a recording in progress and,
 * as each checkpoint becomes final ([liveCheckpoints]), builds its files with
 * the very [UploadPreparer] the Finish send uses and stages them through
 * [ReadyUploader], which owns consent and the network policy.
 *
 * Best effort by construction. Staging only ever moves bytes the Finish send
 * would move anyway, so every failure here (offline, a refused network, a
 * server error) just leaves the checkpoint for the next pass or for Finish;
 * nothing in this class can fail or slow the recording. A checkpoint counts as
 * done only once its batch is staged, so a refused network is retried on the
 * next pass, not forgotten.
 *
 * Callers must only create one with a face detector behind [preparer]: the
 * consent copy promises face-filtered strips, and an unfiltered stager would
 * break that promise silently.
 */
class WalkStager(
    private val cfg: EchoConfig,
    private val drafts: DraftRepository,
    private val draftId: String,
    private val preparer: UploadPreparer,
    private val uploader: ReadyUploader,
    private val samEnabled: suspend () -> Boolean,
    private val liveLog: () -> CaptureLog?,
    private val liveLedger: () -> List<GlimpseEntry>,
    private val everyM: Double = EVALUATE_EVERY_M,
) {
    private val staged = mutableSetOf<String>()

    /** Evaluated once, on the first checkpoint: SAM on the server decides whether clips are built. */
    private var sam: Boolean? = null

    /**
     * Follows the walker's odometer, evaluating every [everyM] metres until the
     * flow ends or the caller cancels. A slow evaluation simply sees a later
     * distance next time: the flow conflates, nothing queues up.
     */
    suspend fun run(distanceM: Flow<Double>) {
        var lastEvaluatedAt = Double.NEGATIVE_INFINITY
        distanceM.collect { d ->
            if (d - lastEvaluatedAt >= everyM) {
                lastEvaluatedAt = d
                evaluate()
            }
        }
    }

    /**
     * One pass: stages every newly final checkpoint as one batch. Returns the
     * outcome, or null when nothing was new. Swallows transport failures (see
     * the class doc) but never cancellation.
     */
    suspend fun evaluate(): ReadyStageOutcome? {
        try {
            val log = liveLog() ?: return null
            val live = liveCheckpoints(log, cfg)
            val fresh = live.closed.filter { it.id !in staged }
            if (fresh.isEmpty()) return null
            val ledger = liveLedger()
            fillKeyframesFromLedger(drafts, draftId, fresh, ledger)
            val prepared = preparer.prepare(
                route = live.route.copy(nodes = fresh),
                log = log,
                samEnabled = sam ?: samEnabled().also { sam = it },
                ledger = ledger,
                useVideo = false,
            )
            val outcome = uploader.stage(prepared.files)
            // Only a batch that is on the server, or one nothing more can be done for, is final.
            if (outcome == ReadyStageOutcome.Staged || outcome == ReadyStageOutcome.SkippedEmpty || outcome == ReadyStageOutcome.SkippedFull) {
                staged += fresh.map { it.id }
            }
            return outcome
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
    }

    companion object {
        /** Metres of walking between passes: a closed checkpoint waits at most this long to start uploading. */
        const val EVALUATE_EVERY_M = 3.0
    }
}
