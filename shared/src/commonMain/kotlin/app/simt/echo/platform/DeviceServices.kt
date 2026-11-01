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
