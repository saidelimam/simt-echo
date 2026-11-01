package app.simt.echo.platform

import androidx.compose.runtime.Composable

/**
 * Device services the Record screen needs, created where the platform
 * context/lifecycle exists (the composition), not in the graph: the
 * camera binds to the screen lifecycle and the motion source owns
 * Android sensors. iOS actuals are stubs — iOS is out of v1 scope.
 */
@Composable
expect fun rememberMotionSource(): MotionSource

@Composable
expect fun rememberCameraRecorder(): CameraRecorder

@Composable
expect fun rememberHaptics(): Haptics

/** Wall-clock epoch milliseconds (capture logs store real time). */
expect fun currentEpochMs(): Long

/** The platform's LAN bundle transport (W2-03), selected once per graph. */
expect fun createPeerTransport(): PeerTransport

/**
 * Whether the device currently has any network path (plan W2-06 offline
 * chip). Polled by the Navigate screen; kept in the composition because
 * Android needs a Context to ask. iOS reports offline: v1 is sensors-only
 * and never claims connectivity it cannot verify.
 */
@Composable
expect fun rememberOnline(pollMs: Long = 5_000L): Boolean

/**
 * Keeps the screen on while navigation is visible (plan W2-06). Android
 * sets FLAG_KEEP_SCREEN_ON for the hosting Activity; iOS is a no-op until
 * W5-03 owns the port.
 */
@Composable
expect fun KeepScreenOn()
