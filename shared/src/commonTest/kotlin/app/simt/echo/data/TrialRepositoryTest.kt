package app.simt.echo.data

import app.simt.echo.model.DriftMarker
import app.simt.echo.model.Trial
import app.simt.echo.model.TrialMode
import app.simt.echo.model.shouldShowLandmarkCards
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** W4-01 test delta: trials persist, drift is signed, CSV needs no hand edits. */
class TrialRepositoryTest {

    private val store = FileStore(FakeFileSystem(), "/data".toPath())
    private val trials = TrialRepository(store)

    private fun trial(id: String, mode: TrialMode) = Trial(
        trialId = id,
        routeId = "route-1",
        mode = mode,
        device = "pixel-8",
        startedAtEpochMs = 1_000L,
        durationS = 61.5,
        distanceM = 62.0,
        arrived = true,
        anchors = 3,
        confirmations = 2,
        uncertainEpisodes = 1,
        offRouteEpisodes = 0,
        baselineMode = mode == TrialMode.SENSOR_ONLY,
        markers = listOf(DriftMarker(20.0, 21.5, 2_000L)),
    )

    @Test
    fun saveListGetDeleteRoundTrip() {
        trials.save(trial("t1", TrialMode.ECHO))
        assertEquals(listOf("t1"), trials.list().map { it.trialId })
        assertEquals(trial("t1", TrialMode.ECHO), trials.get("t1"))
        trials.delete("t1")
        assertTrue(trials.list().isEmpty())
        assertNull(trials.get("t1"))
    }

    @Test
    fun driftErrorIsSignedAheadPositive() {
        assertEquals(1.5, DriftMarker(20.0, 21.5, 0L).errorM)
        assertEquals(-1.5, DriftMarker(21.5, 20.0, 0L).errorM)
    }

    @Test
    fun baselineHidesLandmarkCards() {
        assertTrue(shouldShowLandmarkCards(baselineMode = false))
        assertFalse(shouldShowLandmarkCards(baselineMode = true))
    }

    @Test
    fun twoTrialsOfEachModeExportToOneCsv() {
        val all = listOf(
            trial("e1", TrialMode.ECHO),
            trial("e2", TrialMode.ECHO),
            trial("s1", TrialMode.SENSOR_ONLY),
            trial("s2", TrialMode.SENSOR_ONLY),
        )
        all.forEach(trials::save)
        val csv = trials.exportCsv()

        val lines = csv.lines().filter { it.isNotEmpty() }
        // Run header + 4 run rows, blank separator, marker header + 4 marker rows.
        assertTrue(lines.first().startsWith("trialId,routeId,mode,"))
        // Run rows carry the route id; marker rows carry distances instead.
        assertEquals(4, lines.count { it.contains("route-1") })
        assertTrue(lines.any { it.startsWith("trialId,expectedM,estimatedM,errorM,") })
        // Every protocol field present: no hand-editing to add a column.
        assertTrue(csv.contains("uncertainEpisodes"))
        assertTrue(csv.contains("offRouteEpisodes"))
        assertTrue(csv.contains("baselineMode"))
        // Drift transcribed with its signed error.
        assertTrue(csv.contains("20.0,21.5,1.5"))
    }

    @Test
    fun csvEscapesCommasAndQuotes() {
        assertEquals("\"a,b\"", Csv.escape("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", Csv.escape("say \"hi\""))
        assertEquals("plain", Csv.escape("plain"))
    }
}
