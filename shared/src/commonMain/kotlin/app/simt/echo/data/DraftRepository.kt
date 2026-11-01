package app.simt.echo.data

import app.simt.echo.model.CaptureLog
import app.simt.echo.model.EchoJson
import app.simt.echo.model.EchoRoute
import kotlinx.serialization.encodeToString

/** In-progress captures under `drafts/<draftId>/` (plan §5.3). */
class DraftRepository(private val store: FileStore) {

    fun saveCapture(log: CaptureLog) {
        store.writeText(captureFile(log.draftId), EchoJson.encodeToString(log))
    }

    fun loadCapture(draftId: String): CaptureLog? {
        val text = store.readText(captureFile(draftId)) ?: return null
        return EchoJson.decodeFromString<CaptureLog>(text)
    }

    fun saveDraftRoute(route: EchoRoute) {
        // Draft routes are keyed by routeId until Save assigns permanence.
        store.writeText(draftRouteFile(route.routeId), EchoJson.encodeToString(route))
    }

    fun loadDraftRoute(routeId: String): EchoRoute? {
        val text = store.readText(draftRouteFile(routeId)) ?: return null
        return EchoJson.decodeFromString<EchoRoute>(text)
    }

    /** Draft ids with a capture present, newest directory name last. */
    fun listDraftIds(): List<String> =
        store.list(DRAFTS_DIR).map { it.name }.sorted()

    fun delete(draftId: String) {
        store.deleteRecursively("$DRAFTS_DIR/$draftId")
    }

    private fun captureFile(draftId: String) = "$DRAFTS_DIR/$draftId/capture.json"

    private fun draftRouteFile(routeId: String) = "$DRAFTS_DIR/$routeId/route.draft.json"

    private companion object {
        const val DRAFTS_DIR = "drafts"
    }
}
