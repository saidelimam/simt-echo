package app.simt.echo.ui.screens.processing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.simt.echo.platform.rememberNotificationPermissionRequest
import app.simt.echo.transfer.JobStage
import app.simt.echo.ui.atmosphere.CanopyPanel
import app.simt.echo.ui.atmosphere.CanopyVariant
import app.simt.echo.ui.components.CosmosErrorState
import app.simt.echo.ui.components.CosmosLoadingState
import app.simt.echo.ui.components.CosmosPrimaryButton
import app.simt.echo.ui.components.CosmosSecondaryButton
import app.simt.echo.ui.components.CosmosSuccessState
import app.simt.echo.ui.components.StatusChip
import app.simt.echo.ui.copy.aiUnavailableCopy
import app.simt.echo.ui.theme.EchoSpace12
import app.simt.echo.ui.theme.EchoSpace16
import app.simt.echo.ui.theme.EchoSpace24

const val ProcessingConsentSheetTag = "ProcessingConsentSheet"
const val ProcessingStageInstrumentTag = "ProcessingStageInstrument"
const val ProcessingProgressTag = "ProcessingProgress"

/**
 * Consent + cloud processing in Cosmos (plan §8.3 / W3-02, slice W8-09):
 * consent as a canopy sheet carrying the frozen privacy copy, then the
 * Working job as the W13-06 constellation (one star per checkpoint from
 * live events, honest stage + frame-budget header, Cancel and Continue
 * without AI) through to Ready. Errors show Retry and Continue without
 * AI; Cancel leaves the persisted job so re-entering resumes polling.
 *
 * Presentation only: every [ProcessingPhase] (incl. the W7-07
 * [ProcessingPhase.AiUnavailable] honest state) and every ViewModel
 * transition is untouched — this file only re-surfaces them.
 *
 * W8-05: the shell owns the heading and the Close chrome.
 */
@Composable
fun ProcessingScreen(
    viewModel: ProcessingViewModel,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    /** True when the view model posts a Live Update (W13-10): Send primes the permission. */
    liveUpdatesEnabled: Boolean = false,
) {
    val ui by viewModel.ui.collectAsState()
    val requestNotifications = rememberNotificationPermissionRequest()
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(EchoSpace24),
        verticalArrangement = Arrangement.spacedBy(EchoSpace12),
    ) {
        when (val phase = ui.phase) {
            ProcessingPhase.Loading -> CosmosLoadingState(title = "Preparing…")
            is ProcessingPhase.Consent -> ConsentSheet(
                info = phase.info,
                hasJob = phase.hasJob,
                onSend = {
                    // Primed in context, from the user's own Send: asked at
                    // most once, and a refusal only means no notification.
                    if (liveUpdatesEnabled) requestNotifications()
                    viewModel.send()
                },
                onNotNow = { viewModel.decline(onDone) },
                onDeleteNow = viewModel::deleteNow,
            )
            ProcessingPhase.Uploading -> {
                // The upload itself reports no byte progress: honest
                // indeterminate bar above the stage list.
                StageInstrument(current = -1, determinate = false)
                CosmosPrimaryButton(label = "Cancel", onClick = onDone, modifier = Modifier.fillMaxWidth())
            }
            is ProcessingPhase.Working -> {
                val constellation by viewModel.constellation.collectAsState()
                ConstellationHeader(
                    stageText = stageLabel(phase.stage),
                    budgetText = constellationBudgetText(
                        constellation.framesUsed,
                        constellation.budgetExhausted,
                    ),
                    onCancel = onDone,
                    onContinueWithoutAi = { viewModel.decline(onDone) },
                )
                ConstellationView(stars = constellation.stars)
            }
            ProcessingPhase.Ready -> {
                StageInstrument(current = 3, determinate = true)
                CosmosSuccessState(
                    title = "Ready",
                    body = "Landmark suggestions are in. Review will show them next.",
                    actionLabel = "Continue",
                    onAction = onDone,
                )
            }
            is ProcessingPhase.Failed -> {
                CosmosErrorState(
                    message = phase.message,
                    actionLabel = "Retry",
                    onAction = viewModel::retry,
                    secondaryLabel = "Continue without AI",
                    onSecondary = { viewModel.decline(onDone) },
                )
            }
            // Unconfigured build (plan W7-07): the honest state, not an
            // error — body copy, one primary path, no cloud progress.
            ProcessingPhase.AiUnavailable -> AiUnavailableBody(
                onContinue = { viewModel.decline(onDone) },
            )
        }
    }
}

/**
 * Consent as a canopy sheet: the frozen W3-02 privacy copy
 * ([consentCopy], byte-identical) on the sheet surface, Send gated on
 * photos exactly as before. Nothing uploads before this sheet's Send —
 * the ViewModel gate is untouched.
 */
@Composable
private fun ConsentSheet(
    info: ConsentInfo,
    hasJob: Boolean,
    onSend: () -> Unit,
    onNotNow: () -> Unit,
    onDeleteNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CanopyPanel(
        variant = CanopyVariant.Sheet,
        modifier = modifier
            .fillMaxWidth()
            .testTag(ProcessingConsentSheetTag),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(EchoSpace24),
            verticalArrangement = Arrangement.spacedBy(EchoSpace12),
        ) {
            Text(
                text = "Before anything uploads",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(text = consentCopy(info), style = MaterialTheme.typography.bodyLarge)
            CosmosPrimaryButton(
                label = "Send",
                onClick = onSend,
                modifier = Modifier.fillMaxWidth(),
                enabled = info.photos > 0,
            )
            CosmosSecondaryButton(label = "Not now", onClick = onNotNow, modifier = Modifier.fillMaxWidth())
            if (hasJob) {
                CosmosSecondaryButton(
                    label = "Delete uploaded files now",
                    onClick = onDeleteNow,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun AiUnavailableBody(
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CanopyPanel(
        variant = CanopyVariant.Flat,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(EchoSpace24),
            verticalArrangement = Arrangement.spacedBy(EchoSpace12),
        ) {
            Text(text = aiUnavailableCopy(), style = MaterialTheme.typography.bodyLarge)
            CosmosPrimaryButton(label = "Continue without AI", onClick = onContinue, modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun stageLabel(stage: JobStage): String = when (stage) {
    JobStage.QUEUED -> "Uploading…"
    JobStage.READING, JobStage.SCOUTING -> "Reading scenes…"
    JobStage.GROUNDING -> "Grounding objects…"
    JobStage.COMPLETE -> "Ready"
    JobStage.ERRORED -> "Failed"
}

/**
 * Staged instrument: the four stages with Done/Now/Todo marks plus a
 * progress bar that is determinate only where honest — stage-level
 * fractions while working or ready, indeterminate while the upload
 * itself is in flight (no byte progress is reported).
 */
@Composable
private fun StageInstrument(
    /** Index of the current stage, -1 while the upload itself is in flight. */
    current: Int,
    determinate: Boolean,
    modifier: Modifier = Modifier,
) {
    val stages = listOf("Uploading", "Reading scenes", "Grounding", "Ready")
    CanopyPanel(
        variant = CanopyVariant.Floating,
        modifier = modifier
            .fillMaxWidth()
            .testTag(ProcessingStageInstrumentTag),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(EchoSpace16)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(EchoSpace12),
        ) {
            if (determinate) {
                val done = (current + 1).coerceIn(0, stages.size)
                LinearProgressIndicator(
                    progress = { done.toFloat() / stages.size },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(ProcessingProgressTag)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                Text(
                    text = "Stage $done of ${stages.size}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(ProcessingProgressTag),
                )
            }
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                stages.forEachIndexed { index, name ->
                    Row(horizontalArrangement = Arrangement.spacedBy(EchoSpace12)) {
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
    }
}
