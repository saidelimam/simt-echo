package app.simt.echo.data

import app.simt.echo.model.EchoJson
import app.simt.echo.model.Trial
import kotlinx.serialization.encodeToString

/**
 * Field-protocol trials under `trials/<trialId>.json` (plan §5.3, §10 W4-01).
 *
 * Same seam as [RouteRepository]: JSON on [FileStore], FakeFileSystem in
 * tests. The CSV export transcribes every trial field plus per-marker drift
 * rows, so the §11 protocol (5 + 5 runs) lands in one file with no
 * hand-editing. `shareFile` (platform share sheet) consumes the returned
 * text; this repository never touches platform APIs.
 */
class TrialRepository(private val store: FileStore) {

    fun list(): List<Trial> =
        store.list(TRIALS_DIR)
            .filter { store.exists("$TRIALS_DIR/${it.name}/$TRIAL_FILE") }
            .mapNotNull { get(it.name) }
            .sortedBy { it.startedAtEpochMs }

    fun get(trialId: String): Trial? {
        val text = store.readText(trialFile(trialId)) ?: return null
        return EchoJson.decodeFromString<Trial>(text)
    }

    fun save(trial: Trial) {
        store.writeText(trialFile(trial.trialId), EchoJson.encodeToString(trial))
    }

    fun delete(trialId: String) {
        store.deleteRecursively("$TRIALS_DIR/$trialId")
    }

    /**
     * One CSV for the whole protocol: a run row per trial, then one drift
     * row per taped marker. Machine-readable with zero hand-editing —
     * every column the protocol names is present, empty only when the run
     * recorded nothing for it.
     */
    fun exportCsv(trials: List<Trial> = list()): String {
        val runRows = Csv.rowsOf(
            trials,
            { it.trialId },
            { it.routeId },
            { it.mode },
            { it.device },
            { it.startedAtEpochMs },
            { it.durationS },
            { it.distanceM },
            { it.arrived },
            { it.anchors },
            { it.confirmations },
            { it.uncertainEpisodes },
            { it.offRouteEpisodes },
            { it.baselineMode },
        )
        val markerRows = trials.flatMap { trial ->
            Csv.rowsOf(
                trial.markers,
                { trial.trialId },
                { it.expectedNodeDistanceM },
                { it.estimatedDistanceM },
                { it.errorM },
                { it.atEpochMs },
            )
        }
        return Csv.table(RUN_HEADERS, runRows) +
            "\n" +
            Csv.table(MARKER_HEADERS, markerRows)
    }

    private fun trialFile(trialId: String) = "$TRIALS_DIR/$trialId/$TRIAL_FILE"

    private companion object {
        const val TRIALS_DIR = "trials"
        const val TRIAL_FILE = "trial.json"
        val RUN_HEADERS = listOf(
            "trialId", "routeId", "mode", "device", "startedAtEpochMs",
            "durationS", "distanceM", "arrived", "anchors", "confirmations",
            "uncertainEpisodes", "offRouteEpisodes", "baselineMode",
        )
        val MARKER_HEADERS = listOf(
            "trialId", "expectedM", "estimatedM", "errorM", "atEpochMs",
        )
    }
}
