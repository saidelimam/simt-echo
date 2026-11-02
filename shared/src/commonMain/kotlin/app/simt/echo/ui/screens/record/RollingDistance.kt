package app.simt.echo.ui.screens.record

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.IntOffset
import app.simt.echo.ui.atmosphere.CosmosMotionDurations
import app.simt.echo.ui.atmosphere.CosmosMotionTier
import app.simt.echo.ui.atmosphere.cosmosMotionTier
import app.simt.echo.ui.motion.LuminousSprings

const val RollingDistanceTag = "EchoWalkDistance"

// Rolling distance digits (freeze §4 count): the odometer value as
// tabular digits that roll per digit on the spatialFast spring when the
// value changes. REDUCED cross-fades; STATIC snaps. The row announces the
// whole value once ("Distance 23.4 m") so TalkBack never spells digits
// (the per-digit Texts are cleared from the semantics tree, not focusable).

@Composable
fun RollingDistance(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    tier: CosmosMotionTier = cosmosMotionTier(),
) {
    Row(
        modifier = modifier
            .testTag(RollingDistanceTag)
            .clearAndSetSemantics { contentDescription = "Distance $text" },
    ) {
        text.forEachIndexed { index, char ->
            RollingDigit(char = char, key = index, color = color, tier = tier)
        }
    }
}

@Composable
private fun RollingDigit(
    char: Char,
    key: Int,
    color: Color,
    tier: CosmosMotionTier,
    modifier: Modifier = Modifier,
) {
    val style = MaterialTheme.typography.displayLarge.copy(fontFeatureSettings = "tnum")
    when (tier) {
        CosmosMotionTier.FULL -> AnimatedContent(
            targetState = char,
            transitionSpec = {
                (slideInVertically(LuminousSprings.spatialFast()) { it / 2 } +
                    fadeIn(LuminousSprings.effectsFast())) togetherWith
                    (slideOutVertically(LuminousSprings.spatialFast()) { -it / 2 } +
                        fadeOut(LuminousSprings.effectsFast()))
            },
            label = "rolling-digit-$key",
        ) { current ->
            Text(text = current.toString(), style = style, color = color, maxLines = 1)
        }
        CosmosMotionTier.REDUCED -> AnimatedContent(
            targetState = char,
            transitionSpec = {
                fadeIn(tween(CosmosMotionDurations.FEEDBACK_MS)) togetherWith
                    fadeOut(tween(CosmosMotionDurations.FEEDBACK_MS))
            },
            label = "rolling-digit-$key",
        ) { current ->
            Text(text = current.toString(), style = style, color = color, maxLines = 1)
        }
        CosmosMotionTier.STATIC -> Text(
            text = char.toString(),
            style = style,
            color = color,
            maxLines = 1,
            modifier = modifier,
        )
    }
}
