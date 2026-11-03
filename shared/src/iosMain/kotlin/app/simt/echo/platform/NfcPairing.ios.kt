package app.simt.echo.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

// iOS is out of v1 scope (plan 0005 §1): every capability is false and the
// controller reports Unsupported, so nothing on this target can render
// "Ready to tap" or claim a tap worked. Ordinary Core NFC reading does not
// establish iPhone card emulation, so NFC pairing is not merely unwired
// here — it is unsupported, and the copy says so.
@Composable
actual fun rememberNfcPairingController(): NfcPairingController =
    remember { UnavailableNfcPairingController() }
