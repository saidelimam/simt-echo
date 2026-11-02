package app.simt.echo.capture

/**
 * Platform face scan over one JPEG (plan W11-04). Android backs it with
 * ML Kit; iOS is owed a Vision implementation behind the same seam.
 *
 * Returns how many faces the image holds. Any failure — undecodable bytes,
 * a model that did not load, a timeout — must throw: [ScanningFaceDetector]
 * turns a throw into [FaceVerdict.UNKNOWN], which is exclude-safe. A scanner
 * must never answer 0 because it could not look. Blocking: call off the
 * main thread.
 */
fun interface FaceScanner {
    fun countFaces(jpeg: ByteArray): Int
}

/**
 * The shippable [FaceDetector]: reads a ledger frame's bytes and asks a
 * [FaceScanner]. Fail-closed at every step — no jpeg path, unreadable
 * bytes or a scan failure all yield [FaceVerdict.UNKNOWN], never
 * [FaceVerdict.FACE_FREE].
 *
 * [FaceDetector] promises the same verdict for the same entry on repeat
 * calls, and callers query one frame several times per upload (flags,
 * strip, keyframes). Verdicts are therefore memoised per frame for this
 * instance's lifetime — one scan per frame, and a failed scan stays
 * excluded rather than flipping on a later query. Use one instance per
 * draft: [readBytes] is bound to it.
 */
class ScanningFaceDetector(
    private val scanner: FaceScanner,
    /** Resolves a ledger entry to its JPEG bytes; null when unreadable. */
    private val readBytes: (GlimpseEntry) -> ByteArray?,
) : FaceDetector {
    private val lock = PoolLock()
    private val verdicts = mutableMapOf<String, FaceVerdict>()

    override fun verdictFor(entry: GlimpseEntry): FaceVerdict {
        val key = entry.jpegPath ?: return FaceVerdict.UNKNOWN
        lock.withLock { verdicts[key] }?.let { return it }
        val verdict = scan(entry)
        return lock.withLock { verdicts.getOrPut(key) { verdict } }
    }

    private fun scan(entry: GlimpseEntry): FaceVerdict {
        val bytes = readBytes(entry) ?: return FaceVerdict.UNKNOWN
        val faces = try {
            scanner.countFaces(bytes)
        } catch (e: Exception) {
            return FaceVerdict.UNKNOWN
        }
        return when {
            faces > 0 -> FaceVerdict.HAS_FACE
            faces == 0 -> FaceVerdict.FACE_FREE
            else -> FaceVerdict.UNKNOWN
        }
    }
}
