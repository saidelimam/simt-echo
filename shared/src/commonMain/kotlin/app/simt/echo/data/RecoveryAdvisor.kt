package app.simt.echo.data

/**
 * Process-death recovery advisor (plan W4-03).
 *
 * Pure inspection over [DraftRepository]'s existing layout — no new
 * storage, no migration:
 * - `capture.json` is autosaved every 10 s during RECORDING (W1-03), so a
 *   kill-mid-capture leaves a draft with samples but no `route.draft.json`.
 * - `job.json` persists the cloud job id + status (W3-02), so a
 *   kill-mid-upload re-entering Processing resumes polling instead of
 *   resubmitting.
 * - `route.draft.json` means the walk finished segmenting and Review can
 *   reopen it.
 *
 * A draft is "recoverable" when it has a capture; the route/job flags say
 * where to resume. Saved routes are not drafts and never appear here.
 */
data class RecoverableDraft(
    val draftId: String,
    val sampleCount: Int,
    val markCount: Int,
    val hasRouteDraft: Boolean,
    val jobStatus: String?,
)

fun listRecoverable(drafts: DraftRepository): List<RecoverableDraft> =
    drafts.listDraftIds().mapNotNull { id ->
        val capture = runCatching { drafts.loadCapture(id) }.getOrNull() ?: return@mapNotNull null
        val route = runCatching { drafts.loadDraftRoute(id) }.getOrNull()
        val job = runCatching { drafts.loadJobRecord(id) }.getOrNull()
        RecoverableDraft(
            draftId = id,
            sampleCount = capture.samples.size,
            markCount = capture.marks.size,
            hasRouteDraft = route != null,
            jobStatus = job?.status,
        )
    }

/**
 * Where the UI sends the user for [draft], as a typed destination:
 * Review when the walk already segmented, resume-recording in the same
 * draft otherwise. (This replaces the original string routes, which the
 * type-safe nav graph cannot parse — review 0003 R2.)
 */
fun RecoverableDraft.destination(): app.simt.echo.ui.EchoRoute =
    if (hasRouteDraft) {
        app.simt.echo.ui.EchoRoute.Review(draftId)
    } else {
        app.simt.echo.ui.EchoRoute.Record(resumeDraftId = draftId)
    }
