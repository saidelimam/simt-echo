package app.simt.echo.model

/**
 * Default spoken instruction for a node (plan §8.4). Sentence case, kept
 * short; never claims certainty the sensors did not earn. Single source of
 * truth: the Segmenter stamps these at capture time and Review refreshes
 * untouched auto-cues against them when the label changes, so both agree.
 */
fun defaultCue(node: RouteNode): String = when (node.type) {
    NodeType.START -> ""
    NodeType.TURN -> {
        val base = when (node.turn) {
            TurnDir.LEFT -> "Turn left"
            TurnDir.RIGHT -> "Turn right"
            TurnDir.U_TURN -> "Make a U-turn"
            null -> "Turn"
        }
        val label = node.label.trim()
        // Turn nodes are labelled "Turn <dir>" by default; only append a
        // real landmark name, never echo the instruction back at itself.
        if (label.isEmpty() || label.firstWord().equals("turn", ignoreCase = true)) base
        else "$base at the $label"
    }
    NodeType.CHECKPOINT ->
        if (node.label.isNotBlank()) "Pass the ${node.label}" else "Pass through"
    NodeType.LEVEL_CHANGE -> {
        val floors = node.levelDelta
        when {
            floors > 1 -> "Go up $floors levels"
            floors == 1 -> "Go up 1 level"
            floors < -1 -> "Go down ${-floors} levels"
            floors == -1 -> "Go down 1 level"
            else -> "Change level"
        }
    }
    NodeType.DESTINATION ->
        if (node.label.isNotBlank()) "Arrive at ${node.label}" else "You have arrived"
}

private fun String.firstWord(): String = trim().split(' ', '\t').firstOrNull().orEmpty()
