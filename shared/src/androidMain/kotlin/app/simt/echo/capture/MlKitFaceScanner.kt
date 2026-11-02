package app.simt.echo.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.TimeUnit

/**
 * ML Kit face scan over a ledger JPEG (plan W11-04). Offline: the model is
 * bundled, nothing leaves the phone to decide whether a frame may upload.
 *
 * Ledger JPEGs are already upright and ≤ 960 px on the long side
 * (`ImageProxy.toLedgerJpeg`), so one pass at 0° is the whole scan. The
 * options lean toward privacy over speed: the accurate mode and a small
 * minimum face (a bystander at a few metres is a face too). Both are
 * unmeasured guesses until the device evidence W11-04 still owes.
 *
 * What this cannot see is a face ML Kit does not detect (turned away,
 * covered, very small or at an extreme angle): the filter drops detectable
 * faces, it does not guarantee there are none. The frozen disclosure
 * (`FACE_FILTER_DISCLOSURE`) is worded to match: frames *showing* faces.
 *
 * Blocking by contract ([FaceScanner]): [Tasks.await] refuses to run on
 * the main thread, which surfaces as a throw and so as UNKNOWN. The client
 * is built on first use and lives for the process.
 */
class MlKitFaceScanner : FaceScanner {
    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setMinFaceSize(MIN_FACE_SIZE)
                .build(),
        )
    }

    override fun countFaces(jpeg: ByteArray): Int {
        val bitmap = decodeBounded(jpeg) ?: error("unreadable jpeg")
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            return Tasks.await(detector.process(image), SCAN_TIMEOUT_S, TimeUnit.SECONDS).size
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeBounded(jpeg: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        val sample = sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_DECODE_SIDE)
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
    }

    internal companion object {
        /** Head width as a share of image width; ML Kit's default is 0.1. */
        const val MIN_FACE_SIZE = 0.05f

        /** A hung scan must not hold the upload: it times out as UNKNOWN. */
        const val SCAN_TIMEOUT_S = 10L

        /** Bytes that are not a ledger frame must not be able to exhaust memory. */
        const val MAX_DECODE_SIDE = 2048
    }
}

/**
 * Power-of-two `inSampleSize` that brings the long side of a [width]×[height]
 * image to at most [maxSide]; 1 for images already within it. Zero or
 * negative dimensions (a failed bounds decode) give 1 and fail later, at
 * the real decode, as an unreadable frame.
 */
internal fun sampleSizeFor(width: Int, height: Int, maxSide: Int): Int {
    var sample = 1
    var side = maxOf(width, height)
    while (side > maxSide) {
        sample *= 2
        side /= 2
    }
    return sample
}
