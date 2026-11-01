package app.simt.echo.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.simt.echo.ui.theme.EchoCardRadius
import app.simt.echo.ui.theme.EchoQrMin

/**
 * White QR card (plan Share screen, ≥ 260 dp). The QR painter arrives
 * with W2-04; the card contract — white surface, centered content,
 * minimum size — is frozen here so Share does not relayout later.
 */
@Composable
fun QrCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {
        Text(
            text = "QR appears here",
            style = MaterialTheme.typography.bodyLarge,
            color = Color(0xFF5B6470),
        )
    },
) {
    Surface(
        shape = RoundedCornerShape(EchoCardRadius),
        color = Color.White,
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .heightIn(min = EchoQrMin)
                .padding(24.dp),
        ) {
            content()
        }
    }
}
