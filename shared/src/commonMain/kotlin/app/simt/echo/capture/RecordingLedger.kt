package app.simt.echo.capture

import app.simt.echo.data.DraftRepository
import app.simt.echo.platform.MotionUpdate
import kotlin.math.abs

/**
 * The Record-time glimpse ledger (plan W11-01/W11-02): a [GlimpseLedger]
 * that stores kept JPEGs in the draft and is fed by the camera's [FrameTap].
 *
 * Owned by one [CaptureSession]. [attach] registers it as the tap's
 * [FrameConsumer]; every frame is scored against the odometer and the turn
 * rate derived from [onMotion], and kept JPEGs are written to
 * `glimpse/<bucket>.jpg` inside draft [draftId] (the stored path is
 * draft-relative, as `readLedgerJpeg` expects). [detach] stops delivery and
 * [persist] writes `glimpse/entries.json`.
 *
 * Threading: [onFrame] runs on the analysis thread, [onMotion] on the
 * motion collector. Motion state and the ledger each sit behind their own
 * lock so a JPEG write never stalls the motion collector.
 *
 * Every frame's [LedgerOffer] outcome is handed to [onOffer] on the analysis
 * thread, after the ledger lock is released. The listener must not block:
 * it is how presentation readers (Record's gold brackets) learn what the
 * ledger kept without touching the ledger or its lock.
 */
class RecordingLedger(
    private val drafts: DraftRepository,
    private val draftId: String,
    private val tap: FrameTap,
    bucketMeters: Double = GlimpseLedger.BUCKET_METERS,
    private val onOffer: (LedgerOffer) -> Unit = {},
) : FrameConsumer {
    private val ledger = GlimpseLedger(bucketMeters, DraftLedgerStore())
    private val ledgerLock = PoolLock()
    private val motionLock = PoolLock()
    private var odometerM = 0.0
    private var omegaRadPerSec = 0.0
    private var lastYawRad: Double? = null
    private var lastMotionNs = 0L

    /** Starts receiving frames from the tap. */
    fun attach() = tap.setConsumer(this)

    /** Stops receiving frames; a frame already in flight still completes. */
    fun detach() = tap.setConsumer(null)

    /** Tracks the latest odometer and yaw rate from consecutive motion updates. */
    fun onMotion(update: MotionUpdate) = motionLock.withLock {
        val previousYaw = lastYawRad
        val dtNs = update.tNs - lastMotionNs
        if (previousYaw != null && dtNs > 0L) {
            omegaRadPerSec = yawRate(previousYaw, update.yawRad, dtNs)
        }
        lastYawRad = update.yawRad
        lastMotionNs = update.tNs
        odometerM = update.distanceM
    }

    override fun onFrame(sample: FrameSample) {
        val (odometer, omega) = motionLock.withLock { odometerM to omegaRadPerSec }
        val offer = ledgerLock.withLock { ledger.offer(sample, odometer, omega) }
        onOffer(offer)
    }

    /**
     * Writes the kept entries to the draft. Skipped when nothing was kept,
     * so a resumed draft never loses the entries of its earlier take to an
     * empty ledger.
     */
    fun persist() {
        val entries = ledgerLock.withLock { ledger.entries() }
        if (entries.isNotEmpty()) drafts.saveGlimpseEntries(draftId, entries)
    }

    /** Writes kept JPEGs into the draft; a failed write degrades to metadata only. */
    private inner class DraftLedgerStore : LedgerStore {
        override fun persist(bucketIndex: Long, tNs: Long, jpeg: ByteArray): String? {
            val name = "$GLIMPSE_DIR/$bucketIndex.jpg"
            return try {
                drafts.writeDraftFile(draftId, name, jpeg)
                name
            } catch (e: Exception) {
                null
            }
        }
    }

    companion object {
        const val GLIMPSE_DIR = "glimpse"
    }
}

/**
 * Magnitude of the turn rate between two yaw readings [dtNs] apart, taking
 * the short way round the circle so a wrap through ±π is not a spin.
 */
internal fun yawRate(fromYawRad: Double, toYawRad: Double, dtNs: Long): Double =
    abs(wrapPi(toYawRad - fromYawRad)) / (dtNs / NANOS_PER_SECOND)

private const val NANOS_PER_SECOND = 1_000_000_000.0
