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
        mistakenConfirmations = 1,
        lighting = "bright, even",
        holdingStyle = "hand, portrait",
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
    fun trialsLiveAtTheFlatSpecPath() {
        trials.save(trial("t1", TrialMode.ECHO))
        assertTrue(store.exists("trials/t1.json"))
    }

    @Test
    fun corruptTrialFileIsSkippedNotFatal() {
        trials.save(trial("good", TrialMode.ECHO))
        store.writeText("trials/broken.json", "{partial write")
        assertEquals(listOf("good"), trials.list().map { it.trialId })
        assertNull(trials.get("broken"))
        assertTrue(trials.exportCsv().contains("good"))
    }

    @Test
    fun fivePlusFiveProtocolExportsOneExactCsv() {
        // The §11 protocol size: 5 ECHO + 5 SENSOR_ONLY, alternating.
        val all = (1..5).flatMap { i ->
            listOf(trial("e$i", TrialMode.ECHO), trial("s$i", TrialMode.SENSOR_ONLY))
        }
        all.forEach(trials::save)
        val csv = trials.exportCsv()

        val lines = csv.lines().filter { it.isNotEmpty() }
        // One header + 10 run rows. No second table, no separator rows:
        // a standard CSV reader sees exactly the protocol.
        assertEquals(11, lines.size)
        assertEquals(
            "trialId,routeId,mode,device,startedAtEpochMs,durationS,distanceM,arrived," +
                "anchors,confirmations,mistakenConfirmations,uncertainEpisodes,offRouteEpisodes," +
                "lighting,holdingStyle,notes,markerCount,meanAbsDriftM,maxAbsDriftM",
            lines.first(),
        )
        // Exact row: quoting for the comma-bearing §11 fields, drift
        // summarised from the marker (signed +1.5 → abs 1.5), mode the
        // single baseline record.
        assertEquals(
            "e1,route-1,ECHO,pixel-8,1000,61.5,62.0,true,3,2,1,1,0," +
                "\"bright, even\",\"hand, portrait\",,1,1.5,1.5",
            lines[1],
        )
        assertTrue(lines.any { it.startsWith("s1,route-1,SENSOR_ONLY,") })
        assertEquals(5, lines.count { ",ECHO," in it })
        assertEquals(5, lines.count { ",SENSOR_ONLY," in it })
    }

    @Test
    fun csvEscapesCommasAndQuotes() {
        assertEquals("\"a,b\"", Csv.escape("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", Csv.escape("say \"hi\""))
        assertEquals("plain", Csv.escape("plain"))
    }
}
