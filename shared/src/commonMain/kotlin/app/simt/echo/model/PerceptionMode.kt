package app.simt.echo.model

/**
 * Ledger mode recorded in `capture.json` (plan W10-03).
 *
 * The fallback ladder is by real bind attempt: `[P,V,A] → [P,V]`. When the
 * full graph binds, the frame ledger is on; when analysis fails to bind,
 * the ledger is off and keyframes come from video as before. Old logs
 * predate the field and decode to [LEDGER_OFF] (the honest value: no
 * ledger ran when they were recorded).
 */
object PerceptionMode {
    /** Preview + Video + Analysis bound; the frame ledger ran. */
    const val LEDGER_ON = "preview_video_analysis"

    /** Preview + Video only; no frame ledger (fallback or pre-W10-03). */
    const val LEDGER_OFF = "preview_video"

    /** Same recording graph, with an admitted visual-motion witness (W16-05). */
    const val LEDGER_VISUAL_ON = "preview_video_analysis_visual"

    fun withVisual(mode: String, used: Boolean): String =
        if (used && mode == LEDGER_ON) LEDGER_VISUAL_ON else mode

    fun usedVisual(mode: String): Boolean = mode == LEDGER_VISUAL_ON
}
