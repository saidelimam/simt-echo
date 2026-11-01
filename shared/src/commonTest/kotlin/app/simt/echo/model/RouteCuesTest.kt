package app.simt.echo.model

import app.simt.echo.model.defaultCue
import app.simt.echo.model.RouteNode
import app.simt.echo.model.TurnDir
import kotlin.test.Test
import kotlin.test.assertEquals

/** W2-01 gate: §8.4 default cues shared by the Segmenter and Review. */
class RouteCuesTest {

    private fun node(
        type: NodeType,
        label: String = "",
        turn: TurnDir? = null,
        levelDelta: Int = 0,
    ) = RouteNode(
        id = "n",
        type = type,
        atDistanceM = 0.0,
        label = label,
        turn = turn,
        levelDelta = levelDelta,
    )

    @Test
    fun startHasNoCue() {
        assertEquals("", defaultCue(node(NodeType.START, label = "Entrance")))
    }

    @Test
    fun turnDefaultLabelIsNotEchoed() {
        assertEquals("Turn right", defaultCue(node(NodeType.TURN, label = "Turn right", turn = TurnDir.RIGHT)))
        assertEquals("Make a U-turn", defaultCue(node(NodeType.TURN, label = "Turn U-turn", turn = TurnDir.U_TURN)))
    }

    @Test
    fun turnUsesDirectionAndLabel() {
        assertEquals(
            "Turn right at the pillar",
            defaultCue(node(NodeType.TURN, label = "pillar", turn = TurnDir.RIGHT)),
        )
        assertEquals(
            "Turn left",
            defaultCue(node(NodeType.TURN, turn = TurnDir.LEFT)),
        )
        assertEquals("Make a U-turn", defaultCue(node(NodeType.TURN, turn = TurnDir.U_TURN)))
    }

    @Test
    fun checkpointPassesLabel() {
        assertEquals("Pass the lobby", defaultCue(node(NodeType.CHECKPOINT, label = "lobby")))
        assertEquals("Pass through", defaultCue(node(NodeType.CHECKPOINT)))
    }

    @Test
    fun levelChangeCountsFloors() {
        assertEquals("Go down 1 level", defaultCue(node(NodeType.LEVEL_CHANGE, levelDelta = -1)))
        assertEquals("Go up 2 levels", defaultCue(node(NodeType.LEVEL_CHANGE, levelDelta = 2)))
        assertEquals("Change level", defaultCue(node(NodeType.LEVEL_CHANGE)))
    }

    @Test
    fun destinationArrivesAtLabel() {
        assertEquals("Arrive at Elevator", defaultCue(node(NodeType.DESTINATION, label = "Elevator")))
        assertEquals("You have arrived", defaultCue(node(NodeType.DESTINATION)))
    }
}
