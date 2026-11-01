package app.simt.echo.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import okio.Path
import okio.Path.Companion.toPath

// iOS is out of v1 scope (plan W5-03 Accepted): stubs keep the target
// compiling without pretending capture exists.

@Composable
actual fun rememberMotionSource(): MotionSource = remember {
    object : MotionSource {
        override val updates: Flow<MotionUpdate> = emptyFlow()
        override fun start() = Unit
        override fun stop() = Unit
    }
}

private class StubCameraRecorder : CameraRecorder {
    private var lastOut: Path = "".toPath()

    @Composable
    override fun Preview(modifier: Modifier) = Unit

    override suspend fun takePhoto(out: Path): Path = out

    override suspend fun startVideo(out: Path): Long {
        lastOut = out
        return 0L
    }

    override suspend fun stopVideo(): Path = lastOut
}

@Composable
actual fun rememberCameraRecorder(): CameraRecorder = remember { StubCameraRecorder() }

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
actual fun currentEpochMs(): Long =
    platform.posix.time(null) * 1000L

actual fun createPeerTransport(): PeerTransport = object : PeerTransport {
    override suspend fun offer(payload: ByteArray, token: String): Endpoint =
        error("LAN transport is Android-only in v1")

    override suspend fun fetch(endpoint: Endpoint, token: String): ByteArray =
        error("LAN transport is Android-only in v1")

    override fun stopOffer() = Unit
}

@Composable
actual fun rememberHaptics(): Haptics = remember {
    object : Haptics {
        override fun play(pattern: HapticPattern) = Unit
    }
}

@Composable
actual fun rememberOnline(pollMs: Long): Boolean = false

@Composable
actual fun KeepScreenOn() = Unit
