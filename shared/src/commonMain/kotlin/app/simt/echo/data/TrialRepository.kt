package app.simt.echo.data

import app.simt.echo.model.EchoJson
import app.simt.echo.model.Trial
import kotlinx.serialization.encodeToString
import okio.Path

/**
 * Field-protocol trials under `trials/<trialId>.json` (plan §5.3, §10 W4-01).
 *
 * Same seam as [RouteRepository]: JSON on [FileStore], FakeFileSystem in
 * tests. The CSV export transcribes every trial field plus per-marker drift
 * rows, so the §11 protocol (5 + 5 runs) lands in one file with no
 * hand-editing. [exportCsvFile] writes the same CSV to `exports/` for
 * the platform share sheet; this repository never touches platform APIs.
 */
class TrialRepository(private val store: FileStore) {

    fun list(): List<Trial> =
        store.list(TRIALS_DIR)
            .filter { it.name.endsWith(".json") }
            .mapNotNull { get(it.name.removeSuffix(".json")) }
            .sortedBy { it.startedAtEpochMs }

    /** One trial, or null when absent/corrupt — a partial write (process
     * death mid-save) must never throw into list/export (review 0002). */
    fun get(trialId: String): Trial? {
        val text = store.readText(trialFile(trialId)) ?: return null
        return runCatching { EchoJson.decodeFromString<Trial>(text) }.getOrNull()
    }

    fun save(trial: Trial) {
        store.writeText(trialFile(trial.trialId), EchoJson.encodeToString(trial))
    }

    fun delete(trialId: String) {
        store.deleteRecursively(trialFile(trialId))
    }

    /**
     * How many files Delete all would remove here: trials plus everything
     * under `exports/`. Read by Settings so the destructive row can be
     * disabled when there is nothing to erase, without parsing every trial.
     */
    fun deletableFileCount(): Int =
        store.list(TRIALS_DIR).count { it.name.endsWith(".json") } +
            store.list(FileStore.EXPORTS_DIR).size

    /**
     * Deletes every trial (W8-11 Delete all). Returns how many were removed.
     */
    fun deleteAll(): Int {
        val count = list().size
        store.deleteRecursively(TRIALS_DIR)
        return count
    }

    /**
     * Deletes every file under `exports/` (W8-11 Delete all: the trials
     * CSV plus share-sheet `.echo` copies). Returns files removed.
     */
    fun clearExports(): Int {
        val count = store.list(FileStore.EXPORTS_DIR).size
        store.deleteRecursively(FileStore.EXPORTS_DIR)
        return count
    }

    /**
     * One parseable CSV for the whole protocol: one header, one row per
     * run, every §11 field a column. Per-marker drift rows stay in the
     * trial JSON (they are per-marker, not per-run); the CSV carries
     * their count and absolute-error summary so drift is reportable from
     * the CSV alone. The run's [Trial.mode] is the single baseline
     * record — there is no second, contradictable flag (review 0002).
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
            { it.mistakenConfirmations },
            { it.uncertainEpisodes },
            { it.offRouteEpisodes },
            { it.lighting },
            { it.holdingStyle },
            { it.notes },
            { it.markers.size },
            { t -> t.markers.map { m -> kotlin.math.abs(m.errorM) }.takeIf { it.isNotEmpty() }?.average() },
            { t -> t.markers.map { m -> kotlin.math.abs(m.errorM) }.takeIf { it.isNotEmpty() }?.maxOrNull() },
        )
        return Csv.table(RUN_HEADERS, runRows)
    }

    /**
     * CSV export file (W6-01, closes the W4-01 residual): writes
     * `exports/trials.csv` and returns its absolute path for
     * `FileSharing.shareFile`. Fixed name: each export replaces the
     * previous plaintext copy, so no purge dance is needed. Null when
     * there are no trials — the caller shows an honest no-op instead of
     * opening a share sheet with nothing in it.
     */
    fun exportCsvFile(): Path? {
        val trials = list()
        if (trials.isEmpty()) return null
        val relative = "${FileStore.EXPORTS_DIR}/trials.csv"
        store.writeText(relative, exportCsv(trials))
        return store.resolvePath(relative)
    }

    private fun trialFile(trialId: String) = "$TRIALS_DIR/$trialId.json"

    private companion object {
        const val TRIALS_DIR = "trials"
        val RUN_HEADERS = listOf(
            "trialId", "routeId", "mode", "device", "startedAtEpochMs",
            "durationS", "distanceM", "arrived", "anchors", "confirmations",
            "mistakenConfirmations", "uncertainEpisodes", "offRouteEpisodes",
            "lighting", "holdingStyle", "notes",
            "markerCount", "meanAbsDriftM", "maxAbsDriftM",
        )
    }
}
