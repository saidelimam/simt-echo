package app.simt.echo.capture

import kotlin.math.floor

/**
 * One ledgered glimpse: the best frame seen so far in its odometer bucket.
 *
 * Metadata is always recorded; [jpegPath] is null when the frame carried no
 * JPEG or the [LedgerStore] declined to persist it.
 */
data class GlimpseEntry(
    val bucketIndex: Long,
    val tNs: Long,
    val odometerM: Double,
    val score: Double,
    val sharp: Double,
    val hash: Long,
    val jpegPath: String?,
)

/** Outcome of one [GlimpseLedger.offer] call. */
sealed interface LedgerOffer {
    val bucketIndex: Long
    val score: Double

    /**
     * Novelty of the frame's hash against the previously kept hash
     * ([FrameQuality.novelty]; 1.0 when it founded history). Carried so UI
     * highlights (W13-04 brackets) can judge "top-decile and novel" without
     * re-reading ledger internals.
     */
    val novelty: Double

    /** The bucket was empty; this frame founded it. */
    data class Kept(
        override val bucketIndex: Long,
        override val score: Double,
        override val novelty: Double,
    ) : LedgerOffer

    /** This frame beat the incumbent and replaced it. */
    data class Replaced(
        override val bucketIndex: Long,
        override val score: Double,
        override val novelty: Double,
        val oldScore: Double,
    ) : LedgerOffer

    /** The incumbent stays; this frame was worse (ties lose). */
    data class Dropped(
        override val bucketIndex: Long,
        override val score: Double,
        override val novelty: Double,
        val keptScore: Double,
    ) : LedgerOffer
}

/**
 * Narrow persistence seam for kept-frame JPEGs.
 *
 * The platform implements this with a file write to
 * `drafts/<routeId>/glimpse/` (frame already scaled to ≤ 960 px and
 * encoded at quality 80 before it reaches here — that transcode is
 * platform work, outside this pure module). [persist] returns the stored
 * path for [GlimpseEntry.jpegPath], or null to keep metadata only.
 */
interface LedgerStore {
    fun persist(bucketIndex: Long, tNs: Long, jpeg: ByteArray): String?
}

/** No persistence: every kept frame is metadata-only ([GlimpseEntry.jpegPath] null). */
object NoOpLedgerStore : LedgerStore {
    override fun persist(bucketIndex: Long, tNs: Long, jpeg: ByteArray): String? = null
}

/**
 * Glimpse ledger (plan W11-01): keeps the best-scoring frame per
 * [bucketMeters] of odometer travel, scored by [FrameQuality].
 *
 * Pure and deterministic: no coroutines, no platform imports, no RNG. The
 * same offer sequence always yields the same kept set — ties lose, so the
 * first frame at a score wins regardless of later duplicates. Novelty is
 * measured against the ledger-wide most-recently-kept hash.
 *
 * Ownership: [offer] takes the sample — it calls [FrameSample.release]
 * exactly once before returning, since the ledger retains scores and
 * hashes but never the luma buffer. The JPEG lambda is invoked only when
 * the frame is kept (never for dropped frames).
 *
 * Allocation: the [FrameQuality] scoring path allocates nothing. [offer]
 * itself appends one sharpness sample and sorts a scratch copy for the
 * route median, and allocates one entry on keep/replace — ledger-thread
 * bookkeeping only, never the analyzer hot path.
 */
class GlimpseLedger(
    val bucketMeters: Double = BUCKET_METERS,
    private val store: LedgerStore = NoOpLedgerStore,
) {
    private val buckets = LinkedHashMap<Long, GlimpseEntry>()
    private val routeSharp = ArrayList<Double>()
    private var lastKeptHash: Long? = null

    /**
     * Scores [sample] (odometer in metres, turn rate in rad/s) and keeps it
     * when its bucket is empty or its score strictly beats the incumbent.
     */
    fun offer(sample: FrameSample, odometerM: Double, omegaRadPerSec: Double = 0.0): LedgerOffer {
        val planeW = sample.width / sample.decimation.coerceAtLeast(1)
        val planeH = sample.height / sample.decimation.coerceAtLeast(1)
        val sharp = FrameQuality.sharpness(sample.luma, planeW, planeH)
        val exposed = FrameQuality.exposureOk(sample.luma)
        val stable = FrameQuality.stability(omegaRadPerSec)
        val hash = FrameQuality.dHash(sample.luma, planeW, planeH)
        val nov = FrameQuality.novelty(hash, lastKeptHash)
        routeSharp.add(sharp)
        val s = FrameQuality.score(sharp, medianOf(routeSharp), exposed, stable, nov)
        val bucket = bucketFor(odometerM)
        val incumbent = buckets[bucket]
        val result: LedgerOffer
        if (incumbent == null) {
            buckets[bucket] = GlimpseEntry(bucket, sample.tNs, odometerM, s, sharp, hash, persistKept(sample, bucket))
            lastKeptHash = hash
            result = LedgerOffer.Kept(bucket, s, nov)
        } else if (s > incumbent.score) {
            buckets[bucket] = incumbent.copy(
                tNs = sample.tNs,
                odometerM = odometerM,
                score = s,
                sharp = sharp,
                hash = hash,
                jpegPath = persistKept(sample, bucket),
            )
            lastKeptHash = hash
            result = LedgerOffer.Replaced(bucket, s, nov, incumbent.score)
        } else {
            result = LedgerOffer.Dropped(bucket, s, nov, incumbent.score)
        }
        sample.release()
        return result
    }

    /** Bucket index for an odometer reading; buckets span [bucketMeters]. */
    fun bucketFor(odometerM: Double): Long = floor(odometerM / bucketMeters).toLong()

    /** Kept entries ordered by bucket; empty until the first offer. */
    fun entries(): List<GlimpseEntry> = buckets.values.sortedBy { it.bucketIndex }

    /** Kept frames so far (one per occupied bucket). */
    fun keptCount(): Int = buckets.size

    private fun persistKept(sample: FrameSample, bucket: Long): String? {
        val encode = sample.jpeg ?: return null
        val bytes = encode() ?: return null
        return store.persist(bucket, sample.tNs, bytes)
    }

    private fun medianOf(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    companion object {
        /** One ledger bucket per half metre of travel (plan W11-01). */
        const val BUCKET_METERS = 0.5
    }
}
