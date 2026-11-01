package app.simt.echo.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.simt.echo.ui.theme.EchoTheme

// Rendered in the IDE via Compose previews (W0-03 done-when: dark/light
// previews render). No logic here — each preview pins one component.

@Preview(name = "Buttons · Dark")
@Composable
private fun ButtonsDarkPreview() {
    EchoTheme(darkTheme = true) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(16.dp),
        ) {
            EchoButton(text = "Record", onClick = {})
            PillButton(text = "Receive", onClick = {})
            RoundActionButton(text = "Start", onClick = {})
        }
    }
}

@Preview(name = "Buttons · Light")
@Composable
private fun ButtonsLightPreview() {
    EchoTheme(darkTheme = false) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(16.dp),
        ) {
            EchoButton(text = "Record", onClick = {})
            PillButton(text = "Receive", onClick = {})
            RoundActionButton(text = "Start", onClick = {})
        }
    }
}

@Preview(name = "Navigate chrome · Dark")
@Composable
private fun NavigateChromeDarkPreview() {
    EchoTheme(darkTheme = true) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(16.dp),
        ) {
            StatusChip(text = "Offline ✓")
            StatusChip(text = "±3 m")
            DistanceReadout(text = "23.4 m")
            DirectionArrow(angleRad = 0.78f)
            LandmarkCard(
                title = "Red pillar",
                cue = "Turn left at the red pillar",
                stats = "SAM 0.86 · tracked 78%",
            )
        }
    }
}

@Preview(name = "Navigate chrome · Light")
@Composable
private fun NavigateChromeLightPreview() {
    EchoTheme(darkTheme = false) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(16.dp),
        ) {
            StatusChip(text = "Offline ✓")
            StatusChip(text = "±3 m")
            DistanceReadout(text = "23.4 m")
            DirectionArrow(angleRad = 0.78f)
            LandmarkCard(
                title = "Red pillar",
                cue = "Turn left at the red pillar",
                stats = "SAM 0.86 · tracked 78%",
            )
        }
    }
}
