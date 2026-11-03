package app.simt.echo.ui.copy

import app.simt.echo.transfer.RouteExpiredException

/**
 * Typed user-error surface (plan W7-06, Cosmos §8.8): every screen error is
 * what happened, what is safe, and the next action. ViewModels keep their
 * `String?` error slots (contracts untouched); they map throwables to one
 * of the fixed human strings below instead of interpolating
 * `exception.message`, server text, or raw ids. Detail stays in code (the
 * caught exception is the log site); copy stays human.
 */
data class UserError(
    val whatHappened: String,
    val whatsSafe: String,
    val nextAction: String,
) {
    fun message(): String = "$whatHappened $whatsSafe $nextAction"
}

/** Fallback display name wherever a route has no saved name (never a raw id). */
const val UNTITLED_WALK = "Untitled walk"

/** Missing-route empty state (Navigate, Align, RouteDetail share one copy). */
val MISSING_ROUTE_ERROR = UserError(
    whatHappened = "Route not found.",
    whatsSafe = "Your saved walks are safe.",
    nextAction = "Go back and open another route.",
)

/** Missing-draft empty state (Review). */
val MISSING_DRAFT_ERROR = UserError(
    whatHappened = "Draft not found.",
    whatsSafe = "Your saved walks are safe.",
    nextAction = "Go back and choose another draft.",
)

/** Share offer failures (plan §8.3): nothing is served until the QR shows. */
fun shareErrorCopy(e: Exception): String = when (e) {
    is RouteExpiredException -> UserError(
        whatHappened = "This route has expired.",
        whatsSafe = "Nothing was shared.",
        nextAction = "Choose another route to share.",
    ).message()
    else -> UserError(
        whatHappened = "Could not start sharing.",
        whatsSafe = "Nothing was shared.",
        nextAction = "Check the connection and try again.",
    ).message()
}

/** Share preview-sheet re-offer / relay failures: the stale QR is already cleared. */
fun shareUpdateErrorCopy(): String = UserError(
    whatHappened = "Could not update sharing.",
    whatsSafe = "Nothing is being shared right now.",
    nextAction = "Try again.",
).message()

/** Share-as-file export failure: only a completed write ever opens the sheet. */
fun shareExportErrorCopy(): String = UserError(
    whatHappened = "Could not export the file.",
    whatsSafe = "Nothing was sent.",
    nextAction = "Try again.",
).message()

/** Receive save failure: the preview stays up so Save can be retried. */
fun receiveSaveErrorCopy(): String = UserError(
    whatHappened = "Could not save the route.",
    whatsSafe = "Nothing was saved.",
    nextAction = "Try saving again.",
).message()

/**
 * Receive fetch found an expired link. Distinct from "can't reach": the
 * peer was reached and answered, and retrying the same link cannot work.
 */
fun receiveExpiredCopy(): String = UserError(
    whatHappened = "This route expired before it arrived.",
    whatsSafe = "Nothing was saved.",
    nextAction = "Ask them for a fresh link.",
).message()

/**
 * Receive fetch reached a bundle that failed its fingerprint or decryption
 * check. Never network advice: a retry would fetch the same broken bytes.
 */
fun receiveUnverifiedCopy(): String = UserError(
    whatHappened = "Couldn't verify this route.",
    whatsSafe = "Nothing was saved.",
    nextAction = "Ask them to share it again.",
).message()

/** Receive file-import failure: no bundle was decoded. */
fun receiveFileErrorCopy(): String = UserError(
    whatHappened = "Could not read the file.",
    whatsSafe = "Nothing was imported.",
    nextAction = "Choose another file and try again.",
).message()

/** Cloud upload/transport failure (plan W3-02): the draft never leaves on failure. */
fun processingErrorCopy(): String = UserError(
    whatHappened = "Cloud upload failed.",
    whatsSafe = "Your draft is safe on this phone.",
    nextAction = "Check the connection and retry.",
).message()

/**
 * Cloud job failure: the server's own error text is never shown (it names
 * models, queues and job internals); the draft on this phone is the safe copy.
 */
fun processingJobErrorCopy(): String = UserError(
    whatHappened = "The cloud job failed.",
    whatsSafe = "Your draft is safe on this phone.",
    nextAction = "Retry or continue without AI.",
).message()

/** Cloud job vanished server-side: resume is impossible, re-send is the path. */
fun processingJobGoneCopy(): String = UserError(
    whatHappened = "The cloud job is gone.",
    whatsSafe = "Your draft is safe on this phone.",
    nextAction = "Send again to retry.",
).message()

/** Cloud job poll timeout: a minutes-long job outlived the poll window. */
fun processingJobTimeoutCopy(): String = UserError(
    whatHappened = "Timed out waiting for the cloud job.",
    whatsSafe = "Your draft is safe on this phone.",
    nextAction = "Retry or continue without AI.",
).message()

/** Processing with no draft on disk: there is nothing to upload. */
fun processingNoDraftCopy(): String = UserError(
    whatHappened = "The draft is gone.",
    whatsSafe = "Nothing was sent.",
    nextAction = "Go back and record again.",
).message()

/**
 * Cloud AI not configured on this build (plan W7-07): every fresh
 * install until W9-01 config exists. Never a network error — the
 * honest state names the missing setup and offers the manual path.
 */
fun aiUnavailableCopy(): String = UserError(
    whatHappened = "Landmark AI isn't set up on this build.",
    whatsSafe = "Your draft is safe on this phone.",
    nextAction = "Continue without AI.",
).message()

/** Settings status line for the cloud AI configuration (plan W7-07). */
fun cloudAiStatusCopy(configured: Boolean): String = if (configured) {
    "Landmark AI is ready."
} else {
    aiUnavailableCopy()
}

/** Review load failure: the draft on disk could not be read. */
fun reviewLoadErrorCopy(): String = UserError(
    whatHappened = "Could not load the draft.",
    whatsSafe = "Nothing was changed.",
    nextAction = "Go back and try again.",
).message()

/** Review AI suggestion failures: the manual flow keeps working underneath. */
fun reviewSuggestionsErrorCopy(): String = UserError(
    whatHappened = "Could not load AI suggestions.",
    whatsSafe = "Your draft is unchanged.",
    nextAction = "Add landmarks by hand instead.",
).message()

/** Review local-model failure: on-device inference did not produce. */
fun reviewLocalErrorCopy(): String = UserError(
    whatHappened = "The local model is unavailable.",
    whatsSafe = "Your draft is unchanged.",
    nextAction = "Add landmarks by hand instead.",
).message()

/** Review use-suggestion failure: the route was not mutated. */
fun reviewUseSuggestionErrorCopy(): String = UserError(
    whatHappened = "Could not use the AI suggestion.",
    whatsSafe = "Nothing was changed.",
    nextAction = "Try again.",
).message()

/** Review draw-overlay save failure: no landmark was attached. */
fun reviewSaveLandmarkErrorCopy(): String = UserError(
    whatHappened = "Could not save the landmark.",
    whatsSafe = "Nothing was saved.",
    nextAction = "Try again.",
).message()

/** Review Save ECHO failure: the draft is still on disk. */
fun reviewSaveErrorCopy(): String = UserError(
    whatHappened = "Could not save the route.",
    whatsSafe = "The draft is safe.",
    nextAction = "Try saving again.",
).message()

/** Record failures: capture state is owned by the session, copy only reports. */
fun recordStartErrorCopy(): String = UserError(
    whatHappened = "Could not start recording.",
    whatsSafe = "Nothing was captured.",
    nextAction = "Check the camera and try again.",
).message()

fun recordStopErrorCopy(): String = UserError(
    whatHappened = "Could not stop recording cleanly.",
    whatsSafe = "Your progress is kept.",
    nextAction = "Try again.",
).message()

fun recordSaveErrorCopy(): String = UserError(
    whatHappened = "Could not save the draft.",
    whatsSafe = "Your recording is kept.",
    nextAction = "Try again.",
).message()

fun recordCameraErrorCopy(): String = UserError(
    whatHappened = "The camera is unavailable.",
    whatsSafe = "Nothing was captured.",
    nextAction = "Check permissions and try again.",
).message()

fun recordResumeErrorCopy(): String = UserError(
    whatHappened = "That interrupted recording could not be reopened.",
    whatsSafe = "Your other walks are safe.",
    nextAction = "Record again or open another draft.",
).message()

/** Settings trial-export failure: no CSV left the phone. */
fun settingsExportErrorCopy(): String = UserError(
    whatHappened = "Could not export the trials.",
    whatsSafe = "Nothing was written.",
    nextAction = "Try again.",
).message()

private val MONTHS = arrayOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

/**
 * Human UTC date/time for an epoch-millis instant ("12 Jun 2026, 14:30 UTC").
 * Pure Kotlin civil-date math (no String.format, no platform formatter), so
 * Receive expiry and Share created/expiry lines never show a raw epoch.
 */
fun formatEpochUtc(epochMs: Long): String {
    // W9-10: pure-Kotlin floor div/mod — java.lang.Math is unavailable on
    // Kotlin/Native (iOS), and this is commonMain. Identical semantics.
    val epochDays = if (epochMs >= 0) epochMs / 86_400_000L else (epochMs - 86_400_000L + 1L) / 86_400_000L
    val dayMs = epochMs - 86_400_000L * epochDays
    val z = epochDays + 719_468L
    val era = if (z >= 0) z / 146_097L else (z - 146_096L) / 146_097L
    val doe = z - era * 146_097L
    val yoe = (doe - doe / 1_460L + doe / 36_524L - doe / 146_096L) / 365L
    var y = yoe + era * 400L
    val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L)
    val mp = (5L * doy + 2L) / 153L
    val d = doy - (153L * mp + 2L) / 5L + 1L
    val m = if (mp < 10) mp + 3 else mp - 9
    if (m <= 2) y += 1
    val hours = dayMs / 3_600_000L
    val minutes = (dayMs % 3_600_000L) / 60_000L
    val minuteStr = if (minutes < 10) "0$minutes" else "$minutes"
    return "$d ${MONTHS[(m - 1).toInt()]} $y, $hours:$minuteStr UTC"
}

/** Receive/Share expiry line for a stored epoch, or the honest no-expiry line. */
fun expiryCopy(expiresAtEpochMs: Long?): String {
    if (expiresAtEpochMs == null) return "No expiry"
    val readable = formatEpochUtc(expiresAtEpochMs)
    return "Expires $readable"
}

/** Share summary created line for a stored epoch. */
fun createdCopy(createdAtEpochMs: Long): String {
    val readable = formatEpochUtc(createdAtEpochMs)
    return "Created $readable"
}
