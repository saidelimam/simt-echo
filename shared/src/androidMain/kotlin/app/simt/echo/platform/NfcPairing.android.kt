package app.simt.echo.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

// W20-01 ships the seam only: the Android presenter (W20-04) and reader
// (W20-05) install the real controller here. Until then this actual is
// explicitly unavailable — a phone with NFC still reports no capabilities,
// so no screen can render a Ready state that would not work.
@Composable
actual fun rememberNfcPairingController(): NfcPairingController =
    remember { UnavailableNfcPairingController() }
