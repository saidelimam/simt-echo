package app.simt.echo.capture.visual

import app.simt.echo.capture.ThermalState
import app.simt.echo.model.EchoRoute
import app.simt.echo.model.NodeType
import app.simt.echo.model.RouteLeg
import app.simt.echo.model.RouteNode
import app.simt.echo.model.StartAnchor
import app.simt.echo.model.TurnDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W18-03 gates: table-driven tests for [VisualScheduler] duty selection across
 * standing, straight low-σ, turn proximity (≤ 8 m), same-direction ambiguity
 * (≤ 6 m), high σ (≥ 3 m), low usability, thermal tiers, and Battery Saver.
 */
class VisualSchedulerTest {

    private data class Case(
        val name: String,
        val isStanding: Boolean = false,
        val sigmaM: Double = 1.0,
        val usability01: Double = 0.85,
        val distanceToTurnM: Double = 25.0,
        val hasAmbiguousTurn: Boolean = false,
        val thermal: ThermalState = ThermalState.NORMAL,
        val batterySaver: Boolean = false,
        val expectedHz: Double,
    )

    @Test
    fun tableDrivenSchedulerSelectsExpectedRates() {
        val cases = listOf(
            Case(name = "standing pauses tap at 0 Hz", isStanding = true, sigmaM = 0.8, expectedHz = 0.0),
            Case(name = "standing at high sigma still 0 Hz", isStanding = true, sigmaM = 4.5, expectedHz = 0.0),
            Case(name = "straight walk with sigma < 2 m runs at 1 Hz", sigmaM = 1.2, distanceToTurnM = 20.0, expectedHz = 1.0),
            Case(name = "turn within 8 m raises rate to 2 Hz", sigmaM = 1.1, distanceToTurnM = 7.5, expectedHz = 2.0),
            Case(name = "medium sigma (2..3 m) raises rate to 2 Hz", sigmaM = 2.4, distanceToTurnM = 30.0, expectedHz = 2.0),
            Case(name = "high sigma (>= 3 m) raises rate to 4 Hz", sigmaM = 3.2, distanceToTurnM = 30.0, expectedHz = 4.0),
            Case(name = "ambiguous turn within 6 m raises rate to 4 Hz", sigmaM = 1.0, distanceToTurnM = 5.0, hasAmbiguousTurn = true, expectedHz = 4.0),
            Case(name = "low usability (< 0.25) backs off high sigma to 1 Hz probe", sigmaM = 3.8, usability01 = 0.15, expectedHz = 1.0),
            Case(name = "MODERATE thermal halves 2 Hz to 1 Hz", sigmaM = 2.2, thermal = ThermalState.MODERATE, expectedHz = 1.0),
            Case(name = "MODERATE thermal halves 4 Hz to 2 Hz", sigmaM = 3.5, thermal = ThermalState.MODERATE, expectedHz = 2.0),
            Case(name = "Battery Saver halves 2 Hz to 1 Hz", sigmaM = 1.0, distanceToTurnM = 6.0, batterySaver = true, expectedHz = 1.0),
            Case(name = "SEVERE thermal stops tap at 0 Hz", sigmaM = 3.5, thermal = ThermalState.SEVERE, expectedHz = 0.0),
        )

        for (c in cases) {
            val intervalNs = VisualScheduler.intervalNs(
                isStanding = c.isStanding,
                sigmaM = c.sigmaM,
                usability01 = c.usability01,
                distanceToTurnM = c.distanceToTurnM,
                hasAmbiguousTurn = c.hasAmbiguousTurn,
                thermal = c.thermal,
                batterySaver = c.batterySaver,
            )
            assertEquals(c.expectedHz, VisualScheduler.rateHz(intervalNs), 1e-6, c.name)
        }
    }

    @Test
    fun routeHelperDetectsTurnProximityAndSameDirectionAmbiguity() {
        val route = EchoRoute(
            routeId = "r-ambig",
            name = "Ambiguous turns",
            createdAtEpochMs = 0L,
            creatorLabel = "test",
            motionSource = "test",
            totalLengthM = 50.0,
            start = StartAnchor("Start", null, "Align"),
            nodes = listOf(
                RouteNode("n0", NodeType.START, 0.0, "Start"),
                RouteNode("t1", NodeType.TURN, 20.0, "First right", turn = TurnDir.RIGHT),
                RouteNode("t2", NodeType.TURN, 25.0, "Second right", turn = TurnDir.RIGHT),
                RouteNode("t3", NodeType.TURN, 40.0, "Far left", turn = TurnDir.LEFT),
                RouteNode("n4", NodeType.DESTINATION, 50.0, "End"),
            ),
            legs = listOf(
                RouteLeg("n0", "t1", 20.0, 0.0),
                RouteLeg("t1", "t2", 5.0, 90.0),
                RouteLeg("t2", "t3", 15.0, 180.0),
                RouteLeg("t3", "n4", 10.0, 90.0),
            ),
            path = emptyList(),
        )

        // At s = 5.0 m (15 m from t1): straight low-sigma -> 1 Hz.
        val straightNs = VisualScheduler.intervalForRouteNs(route, sM = 5.0, isStanding = false, sigmaM = 1.0)
        assertEquals(1.0, VisualScheduler.rateHz(straightNs), 1e-6)

        // At s = 15.0 m (5 m from t1, which clashes with t2 at 25.0 m within 6 m): ambiguous -> 4 Hz.
        val ambigNs = VisualScheduler.intervalForRouteNs(route, sM = 15.0, isStanding = false, sigmaM = 1.0)
        assertEquals(4.0, VisualScheduler.rateHz(ambigNs), 1e-6)

        // At s = 35.0 m (5 m from isolated t3 at 40.0 m): near turn, not ambiguous -> 2 Hz.
        val turnNs = VisualScheduler.intervalForRouteNs(route, sM = 35.0, isStanding = false, sigmaM = 1.0)
        assertEquals(2.0, VisualScheduler.rateHz(turnNs), 1e-6)
        assertTrue(VisualScheduler.rateHz(straightNs) <= 1.0)
    }
}
