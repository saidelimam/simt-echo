package app.simt.echo.sim

import app.simt.echo.model.NavigatorEngine
import java.io.File
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MonteCarloBaselineHostTest {
    @Test
    fun seededBaselinePasses() {
        val baseline = readBaseline()
        val walkCount = System.getProperty(WALK_COUNT_PROPERTY)?.toIntOrNull() ?: SimWalk.DEFAULT_FAST_WALKS
        val baselineRows = when (walkCount) {
            SimWalk.DEFAULT_FAST_WALKS -> baseline.fast
            SimWalk.DEFAULT_FULL_WALKS -> baseline.full
            else -> error("Supported simulation gates are 100 or 500 walks, received $walkCount")
        }
        val measuredRows = MonteCarloGate.runSuite(listOf(walkCount), baseline.seedStart).getValue(walkCount)

        val expectedKeys = SimEnvironment.entries.flatMap { scenario ->
            NavigatorEngine.entries.map { engine -> scenario to engine }
        }.toSet()
        val baselineByKey = baselineRows.associateBy { it.scenario to it.engine }
        val measuredByKey = measuredRows.associateBy { it.scenario to it.engine }
        assertEquals(expectedKeys.size, baselineRows.size)
        assertEquals(expectedKeys.size, measuredRows.size)
        assertEquals(expectedKeys, baselineByKey.keys)
        assertEquals(expectedKeys, measuredByKey.keys)
        for (reference in baselineRows) {
            val measured = assertNotNull(
                measuredByKey[reference.scenario to reference.engine],
                "Missing measurement for ${reference.scenario}/${reference.engine}",
            )
            assertGate(measured, reference)
        }
    }

    @Test
    fun recordBaseline() {
        if (System.getProperty(RECORD_PROPERTY) != "true") {
            readBaseline()
            return
        }

        val measured = MonteCarloGate.runSuite(
            listOf(SimWalk.DEFAULT_FAST_WALKS, SimWalk.DEFAULT_FULL_WALKS),
            SimWalk.BASELINE_SEED,
        )
        val baseline = AccuracyBaseline(
            fast = measured.getValue(SimWalk.DEFAULT_FAST_WALKS),
            full = measured.getValue(SimWalk.DEFAULT_FULL_WALKS),
        )
        (baseline.fast + baseline.full).forEach { assertGate(it, it) }
        val artifact = baselineFile()
        val parent = artifact.parentFile
        if (parent != null && !parent.exists()) check(parent.mkdirs()) { "Could not create $parent" }
        artifact.writeText(JSON.encodeToString(baseline) + "\n")
        assertTrue(artifact.length() > 0L)
    }

    private fun readBaseline(): AccuracyBaseline {
        val artifact = baselineFile()
        assertTrue(artifact.isFile, "Missing ${artifact.path}; record the seeded baseline first")
        val baseline = JSON.decodeFromString<AccuracyBaseline>(artifact.readText())
        assertEquals(AccuracyBaseline.BASELINE_SCHEMA_VERSION, baseline.schemaVersion)
        assertEquals("sim", baseline.evidenceType)
        assertEquals(SimWalk.BASELINE_SEED, baseline.seedStart)
        assertEquals(MonteCarloGate.BASELINE_ROUTE_LENGTH_M, baseline.navigationRouteLengthM)
        assertEquals(MonteCarloGate.ARRIVAL_SCORE_DISTANCE_M, baseline.arrivalCheckpointM)
        assertEquals(SimWalk.DEFAULT_FAST_WALKS, baseline.fastWalksPerScenario)
        assertEquals(SimWalk.DEFAULT_FULL_WALKS, baseline.fullWalksPerScenario)
        return baseline
    }

    private fun assertGate(measured: ScenarioMetrics, reference: ScenarioMetrics) {
        val result = MonteCarloGate.evaluate(measured, reference)
        assertTrue(result.passed, "${reference.scenario}/${reference.engine} failed: ${result.failures}")
    }

    private fun baselineFile(): File {
        val root = System.getProperty(ROOT_PROPERTY) ?: System.getProperty("user.dir")
        return File(root, BASELINE_PATH)
    }

    companion object {
        private val JSON = Json {
            prettyPrint = true
            encodeDefaults = true
        }

        private const val ROOT_PROPERTY = "simt.repositoryRoot"
        private const val WALK_COUNT_PROPERTY = "simt.baseline.walks"
        private const val RECORD_PROPERTY = "simt.baseline.record"
        private const val BASELINE_PATH = "docs/perf/accuracy-baseline.json"
    }
}
