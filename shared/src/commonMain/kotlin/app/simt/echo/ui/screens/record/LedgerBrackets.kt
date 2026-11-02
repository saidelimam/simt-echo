package app.simt.echo.ui.screens.record

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.simt.echo.ui.atmosphere.CosmosMotionDurations
import app.simt.echo.ui.atmosphere.CosmosMotionTier
import app.simt.echo.ui.atmosphere.cosmosMotionTier
import app.simt.echo.ui.motion.LuminousSprings
import app.simt.echo.ui.theme.EchoExtras
import kotlinx.coroutines.delay

const val LedgerBracketsTag = "EchoWalkBrackets"

/** Honest highlight copy: a great view, never an object claim. */
const val LEDGER_BRACKET_COPY = "Great view captured"

/** Bracket arm length. */
private val BracketArm = 28.dp

/** Bracket stroke. */
private val BracketStroke = 3.dp

/** Bracket inset from the viewport edges. */
private val BracketInset = 24.dp

// Gold corner brackets: shown for LEDGER_BRACKET_MS when a ledger frame
// is top-decile and novel (see EchoWalkState mapping). FULL fades in on
// the effects spring; REDUCED fades in 150 ms; STATIC is already there —
// same layout and copy in every tier, same final state (gone, announced).
// [onExpired] fires once per highlight so the parent clears the signal.

@Composable
fun LedgerBrackets(
    highlight: LedgerHighlight?,
    modifier: Modifier = Modifier,
    tier: CosmosMotionTier = cosmosMotionTier(),
    onExpired: () -> Unit = {},
) {
    if (highlight == null) return
    val gold = EchoExtras.gold
    val alpha = remember(highlight) {
        Animatable(if (tier == CosmosMotionTier.STATIC) 1f else 0f)
    }
    LaunchedEffect(highlight, tier) {
        when (tier) {
            CosmosMotionTier.FULL -> alpha.animateTo(1f, LuminousSprings.effectsFast())
            CosmosMotionTier.REDUCED -> alpha.animateTo(1f, tween(CosmosMotionDurations.FEEDBACK_MS))
            CosmosMotionTier.STATIC -> alpha.snapTo(1f)
        }
        delay(LEDGER_BRACKET_MS.toLong())
        onExpired()
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag(LedgerBracketsTag)
            .semantics(mergeDescendants = true) { contentDescription = LEDGER_BRACKET_COPY },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val inset = BracketInset.toPx()
            val arm = BracketArm.toPx()
            val stroke = BracketStroke.toPx()
            val a = gold.copy(alpha = alpha.value)
            val corners = listOf(
                Offset(inset, inset) to (1f to 1f),
                Offset(size.width - inset, inset) to (-1f to 1f),
                Offset(inset, size.height - inset) to (1f to -1f),
                Offset(size.width - inset, size.height - inset) to (-1f to -1f),
            )
            for ((origin, dir) in corners) {
                val (dx, dy) = dir
                drawLine(a, origin, origin + Offset(arm * dx, 0f), stroke, StrokeCap.Round)
                drawLine(a, origin, origin + Offset(0f, arm * dy), stroke, StrokeCap.Round)
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 72.dp),
        ) {
            Text(
                text = LEDGER_BRACKET_COPY,
                style = MaterialTheme.typography.labelLarge,
                color = gold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}
