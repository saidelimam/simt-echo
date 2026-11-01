package app.simt.echo.ui.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.simt.echo.ui.theme.EchoPillRadius
import app.simt.echo.ui.theme.EchoRecordButton
import app.simt.echo.ui.theme.EchoTouchPrimary

/**
 * Primary pill action (plan §8.1: ≥ 56 dp touch target, pill radius).
 * One action per screen; words few.
 */
@Composable
fun EchoButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(EchoPillRadius),
        modifier = modifier.heightIn(min = EchoTouchPrimary),
    ) {
        Text(text = text)
    }
}

/** Secondary pill action. Pair with [EchoButton], never two primaries. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(EchoPillRadius),
        modifier = modifier.heightIn(min = EchoTouchPrimary),
    ) {
        Text(text = text)
    }
}

/**
 * 88 dp round capture action (plan §8.3 Record screen). Content is a short
 * label so screen readers name the action; decorative icons pass null.
 */
@Composable
fun RoundActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: androidx.compose.material3.ButtonColors = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ),
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        colors = colors,
        modifier = modifier
            .heightIn(min = EchoRecordButton),
    ) {
        Text(text = text)
    }
}
