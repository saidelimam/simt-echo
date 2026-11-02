package app.simt.echo.ui.screens.record

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.simt.echo.capture.CaptureSession
import app.simt.echo.capture.CaptureState
import app.simt.echo.data.DraftRepository
import app.simt.echo.model.EchoConfig
import app.simt.echo.platform.CameraPermissionGate
import app.simt.echo.platform.KeepScreenOn
import app.simt.echo.platform.currentEpochMs
import app.simt.echo.platform.rememberCameraRecorder
import app.simt.echo.platform.rememberHaptics
import app.simt.echo.platform.rememberMotionSource
import app.simt.echo.ui.components.DistanceReadout
import app.simt.echo.ui.components.EchoButton
import app.simt.echo.ui.components.PillButton
import app.simt.echo.ui.components.RoundActionButton
import app.simt.echo.ui.components.StatusChip
import app.simt.echo.ui.components.oneDecimal
import app.simt.echo.ui.theme.EchoTouchOther
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.random.Random

/**
 * Record screen (plan §8.3 / W1-03): live preview, giant distance,
 * Mark spot, long-press Finish, then the finish form (route name +
 * destination) as an inline panel — no dialog windows.
 *
 * W4-03: keeps the screen on while recording and fails closed behind
 * [CameraPermissionGate] (denied camera shows rationale, never a crash).
 */
@Composable
fun RecordScreen(
    drafts: DraftRepository,
    onSaved: (String) -> Unit,
    modifier: Modifier = Modifier,
    config: EchoConfig = EchoConfig(),
    resumeDraftId: String? = null,
) {
    val motion = rememberMotionSource()
    val camera = rememberCameraRecorder()
    val haptics = rememberHaptics()
    val session = remember(motion, camera, haptics, drafts, config, resumeDraftId) {
        CaptureSession(
            motion = motion,
            camera = camera,
            haptics = haptics,
            drafts = drafts,
            scope = CoroutineScope(Dispatchers.Default),
            nowMs = { currentEpochMs() },
            newDraftId = { "draft-${Random.nextInt(1_000_000)}" },
            config = config,
        )
    }
    // Back-stack-scoped (review 0001 pattern): rotation/process handling
    // must not silently drop the session behind a `remember`.
    val viewModel: RecordViewModel = viewModel(
        key = resumeDraftId ?: "new",
        factory = viewModelFactory {
            addInitializer(RecordViewModel::class) {
                RecordViewModel(session)
            }
        },
    )
    val uiState by viewModel.uiState.collectAsState()
    LaunchedEffect(Unit) { viewModel.prepare(resumeDraftId) }
    KeepScreenOn()

    CameraPermissionGate {
        Box(modifier = modifier.fillMaxSize()) {
            camera.Preview(modifier = Modifier.fillMaxSize(), onError = viewModel::onCameraError)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusChip(text = "${formatElapsed(uiState.hud.elapsedMs)}")
                    StatusChip(text = "${uiState.hud.steps} steps")
                    if (uiState.hud.marks > 0) StatusChip(text = "${uiState.hud.marks} spots")
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    uiState.error?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    DistanceReadout(text = "${oneDecimal(uiState.hud.distanceM)} m")
                    when (uiState.state) {
                        CaptureState.READY -> RoundActionButton(
                            text = "Start",
                            onClick = viewModel::start,
                        )

                        CaptureState.RECORDING -> {
                            PillButton(text = "Mark spot", onClick = viewModel::mark)
                            FinishButton(onFinish = viewModel::beginFinish)
                        }

                        CaptureState.FINISHING, CaptureState.SAVED -> FinishForm(
                            onSave = { name, destination ->
                                viewModel.finish(name, destination, onSaved)
                            },
                        )

                        else -> Text(
                            text = "Stand at the start, face where you'll walk",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FinishForm(onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var destination by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Route name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = destination,
            onValueChange = { destination = it },
            label = { Text("Destination label") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        EchoButton(
            text = "Save ECHO draft",
            onClick = { onSave(name, destination) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Long-press (≥ 600 ms is the gesture recognizer's long-press) to finish.
 * 48 dp target with a Button role; the semantics onClick gives TalkBack a
 * standard activation path, while sighted touch users keep the gesture.
 */
@Composable
private fun FinishButton(onFinish: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .sizeIn(minHeight = EchoTouchOther)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = "Hold to finish"
                onClick { onFinish(); true }
                onLongClick { onFinish(); true }
            }
            .padding(vertical = 8.dp)
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = { onFinish() })
            },
    ) {
        Text(
            text = "Hold to finish",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

private fun formatElapsed(ms: Long): String {
    val totalSeconds = ms / 1000
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}
