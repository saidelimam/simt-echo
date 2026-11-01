package app.simt.echo.ui.screens.processing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.simt.echo.transfer.JobStage
import app.simt.echo.ui.components.EchoButton
import app.simt.echo.ui.components.ErrorState
import app.simt.echo.ui.components.PillButton
import app.simt.echo.ui.components.StatusChip

/**
 * Consent + cloud processing (plan §8.3 / W3-02): the plan-mandated
 * consent copy before anything uploads, then the four stages —
 * Uploading · Reading scenes · Grounding · Ready — driven by the job.
 * Errors show Retry and Continue without AI; Cancel leaves the persisted
 * job so re-entering resumes polling.
 */
@Composable
fun ProcessingScreen(
    viewModel: ProcessingViewModel,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.ui.collectAsState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "Cloud landmarks", style = MaterialTheme.typography.titleLarge)
        when (val phase = ui.phase) {
            ProcessingPhase.Loading -> Text(text = "Preparing…")
            is ProcessingPhase.Consent -> ConsentBody(
                info = phase.info,
                hasJob = phase.hasJob,
                onSend = viewModel::send,
                onNotNow = { viewModel.decline(onDone) },
                onDeleteNow = viewModel::deleteNow,
            )
            ProcessingPhase.Uploading -> {
                StageList(current = -1)
                EchoButton(text = "Cancel", onClick = onDone, modifier = Modifier.fillMaxWidth())
            }
            is ProcessingPhase.Working -> {
                StageList(current = stageIndex(phase.stage))
                Text(text = stageLabel(phase.stage), style = MaterialTheme.typography.bodyLarge)
                PillButton(text = "Cancel", onClick = onDone, modifier = Modifier.fillMaxWidth())
            }
            ProcessingPhase.Ready -> {
                StageList(current = 3)
                Text(text = "Landmark suggestions are in. Review will show them next.")
                EchoButton(text = "Continue", onClick = onDone, modifier = Modifier.fillMaxWidth())
            }
            is ProcessingPhase.Failed -> {
                ErrorState(
                    message = phase.message,
                    actionLabel = "Retry",
                    onAction = viewModel::retry,
                    secondaryLabel = "Continue without AI",
                    onSecondary = { viewModel.decline(onDone) },
                )
            }
        }
    }
}

@Composable
private fun ConsentBody(
    info: ConsentInfo,
    hasJob: Boolean,
    onSend: () -> Unit,
    onNotNow: () -> Unit,
    onDeleteNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = consentCopy(info), style = MaterialTheme.typography.bodyLarge)
        EchoButton(
            text = "Send",
            onClick = onSend,
            modifier = Modifier.fillMaxWidth(),
            enabled = info.photos > 0,
        )
        PillButton(text = "Not now", onClick = onNotNow, modifier = Modifier.fillMaxWidth())
        if (hasJob) {
            PillButton(
                text = "Delete uploaded files now",
                onClick = onDeleteNow,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun stageIndex(stage: JobStage): Int = when (stage) {
    JobStage.QUEUED -> 0
    JobStage.READING, JobStage.SCOUTING -> 1
    JobStage.GROUNDING -> 2
    JobStage.COMPLETE -> 3
    JobStage.ERRORED -> 2
}

private fun stageLabel(stage: JobStage): String = when (stage) {
    JobStage.QUEUED -> "Uploading…"
    JobStage.READING, JobStage.SCOUTING -> "Reading scenes…"
    JobStage.GROUNDING -> "Grounding objects…"
    JobStage.COMPLETE -> "Ready"
    JobStage.ERRORED -> "Failed"
}

@Composable
private fun StageList(
    /** Index of the current stage, -1 while the upload itself is in flight. */
    current: Int,
    modifier: Modifier = Modifier,
) {
    val stages = listOf("Uploading", "Reading scenes", "Grounding", "Ready")
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        stages.forEachIndexed { index, name ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(text = if (index < current) "Done" else if (index == current) "Now" else "Todo")
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (index <= current) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}
