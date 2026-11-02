package app.simt.echo.capture

/**
 * Platform face-detector binding (plan W11-04) — stub.
 *
 * Returns [NoOpFaceDetector] (every frame [FaceVerdict.UNKNOWN], which is
 * exclude-safe) because ML Kit face detection
 * (`com.google.mlkit:face-detection`) is not in the version catalog — only
 * `barcode-scanning` is. Do NOT pass this into a filtering path: it would
 * drop every frame. Production call sites pass `null` (unfiltered) until
 * a later slice adds the catalog dependency and implements the real scan
 * over ledger JPEG bytes here:
 *
 *     // Owed: replace with ML Kit face detection once
 *     // `com.google.mlkit:face-detection` lands in libs.versions.toml
 *     // (additive dependency; barcode-scanning 17.3.0 is the precedent).
 *     // The implementation scans the bytes at `entry.jpegPath`
 *     // (draft-relative, via DraftRepository) and maps:
 *     // faces found -> HAS_FACE, none -> FACE_FREE,
 *     // scan failure/unreadable bytes -> UNKNOWN (exclude-safe).
 */
fun androidFaceDetector(): FaceDetector = NoOpFaceDetector
