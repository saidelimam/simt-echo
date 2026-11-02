package app.simt.echo.ui.screens.navigate

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.simt.echo.data.RouteRepository
import app.simt.echo.data.TrialRepository
import app.simt.echo.model.EchoConfig
import app.simt.echo.model.shouldShowLandmarkCards
import app.simt.echo.nav.NavUi
import app.simt.echo.platform.KeepScreenOn
import app.simt.echo.platform.MotionSource
import app.simt.echo.platform.rememberCameraRecorder
import app.simt.echo.platform.rememberHaptics
import app.simt.echo.platform.rememberMotionSource
import app.simt.echo.platform.rememberOnline
import app.simt.echo.ui.components.CameraNavigateOverlay
import app.simt.echo.ui.components.CosmosBanner
import app.simt.echo.ui.components.CropImage
import app.simt.echo.ui.components.DirectionArrow
import app.simt.echo.ui.components.DistanceReadout
import app.simt.echo.ui.components.CosmosPrimaryButton
import app.simt.echo.ui.components.CosmosEmptyState
import app.simt.echo.ui.components.LandmarkCard
import app.simt.echo.ui.components.CosmosSecondaryButton
import app.simt.echo.ui.components.CosmosTertiaryButton
import app.simt.echo.ui.components.StatusChip
import app.simt.echo.ui.components.oneDecimal
import app.simt.echo.ui.components.overlayScrimAlpha
import app.simt.echo.ui.copy.MISSING_ROUTE_ERROR
import app.simt.echo.ui.theme.EchoSpace12
import app.simt.echo.ui.theme.EchoSpace24

const val NavigateReticleTag = "NavigateReticle"

/**
 * Navigate screen (plan §8.3 / W2-06, Cosmos W8-10): reticle-framed arrow,
 * instruction + tabular distance, σ/offline chips (mark + text, never
 * hue-only), landmark card, progress with node dots, contextual actions.
 * Renders [NavUi] directly — the 1:1 phase mapping (especially UNCERTAIN)
 * is structural ([navigatePhasePresentation], asserted per branch), not
 * per-branch UI code. Drives [motionSource] when injected (W7-04 Simulate
 * replay), else the live motion source; replay coverage lives in the engine
 * tests (W2-05) and the ViewModel gate below.
 *
 * @param researchMode gates the "At marker" trial tap (Settings/research,
 * W8-11 wires it). Defaults off: product UI never shows trial
 * instrumentation; the tap still saves via `NavigateViewModel.atMarker`.
 */
@Composable
fun NavigateScreen(
    routeId: String,
    routes: RouteRepository,
    onArrived: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    config: EchoConfig = EchoConfig(),
    trials: TrialRepository? = null,
    device: String = "unknown",
    baselineMode: Boolean = config.baselineMode,
    researchMode: Boolean = false,
    /** Injected source (W7-04 Simulate); null selects the live source. */
    motionSource: MotionSource? = null,
) {
    val motion = motionSource ?: rememberMotionSource()
    val haptics = rememberHaptics()
    val online = rememberOnline()
    KeepScreenOn()
    // Scoped to this navigation back-stack entry, not `remember`: popping
    // the destination must call onCleared() so motion stops and the
    // collectors die with the screen (review 0001 R2).
    val viewModel: NavigateViewModel = viewModel(
        key = routeId,
        factory = viewModelFactory {
            addInitializer(NavigateViewModel::class) {
                NavigateViewModel(routeId, routes, motion, haptics, config, trials, device, baselineMode, onArrived = onArrived)
            }
        },
    )
    val ui by viewModel.ui.collectAsState()
    LaunchedEffect(Unit) {
        viewModel.start()
        viewModel.align()
    }

    val nav = ui
    if (nav == null) {
        CosmosEmptyState(
            title = MISSING_ROUTE_ERROR.whatHappened,
            actionLabel = "Back",
            onAction = onBack,
            modifier = modifier.fillMaxSize(),
            body = "${MISSING_ROUTE_ERROR.whatsSafe} ${MISSING_ROUTE_ERROR.nextAction}",
        )
        return
    }

    if (config.cameraOverlayEnabled) {
        NavigateOverlayBody(
            nav = nav,
            routes = routes,
            routeId = routeId,
            online = online,
            legCount = viewModel.legCount,
            researchMode = researchMode,
            viewModel = viewModel,
            modifier = modifier,
        )
        return
    }

    NavigateBody(
        nav = nav,
        routes = routes,
        routeId = routeId,
        online = online,
        legCount = viewModel.legCount,
        researchMode = researchMode,
        viewModel = viewModel,
        modifier = modifier,
    )
}

@Composable
private fun NavigateBody(
    nav: NavUi,
    routes: RouteRepository,
    routeId: String,
    online: Boolean,
    legCount: Int,
    researchMode: Boolean,
    viewModel: NavigateViewModel,
    modifier: Modifier = Modifier,
) {
    val showCards = shouldShowLandmarkCards(viewModel.baselineMode)
    val presentation = navigatePhasePresentation(
        phase = nav.phase,
        hasLandmark = nav.landmark != null,
        isLastLeg = viewModel.isLastLeg(nav),
        showCards = showCards,
        researchMode = researchMode,
    )
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(EchoSpace24),
        verticalArrangement = Arrangement.spacedBy(EchoSpace12),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!online) StatusChip(text = "⚠ Offline · sensors only")
            StatusChip(text = "σ ±${oneDecimal(nav.uncertaintyM)} m")
        }
        if (presentation.bannerKind != null) {
            CosmosBanner(message = nav.instruction, kind = presentation.bannerKind)
        }
        // Reticle frame keeps the 200 dp chevron legible in motion and
        // outdoors; the instruction Text below carries the semantics.
        Box(
            modifier = Modifier
                .border(2.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp))
                .padding(8.dp)
                .testTag(NavigateReticleTag),
        ) {
            DirectionArrow(angleRad = nav.arrowRad.toFloat(), contentDescription = null)
        }
        AnimatedContent(targetState = nav.instruction, label = "instruction") { instruction ->
            Text(
                text = instruction,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
        }
        DistanceReadout(text = "${oneDecimal(maxOf(0.0, nav.distanceToNextM))} m")
        // Baseline (SENSOR_ONLY) runs show no landmark cards: turns and
        // distance only, so the comparison against ECHO stays honest.
        // Generic enter/exit only — no per-screen animation system.
        val landmark = if (showCards) nav.landmark else null
        AnimatedVisibility(
            visible = landmark != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
            label = "landmark",
        ) {
            landmark?.let {
                val cropBytes = remember(it.cropAssetId) {
                    it.cropAssetId?.let { id -> routes.readAsset(routeId, id) }
                }
                if (cropBytes != null) {
                    CropImage(bytes = cropBytes, contentDescription = it.title)
                }
                LandmarkCard(
                    title = it.title,
                    cue = it.cue,
                    stats = "in ~${oneDecimal(maxOf(0.0, nav.distanceToNextM))} m",
                )
            }
        }
        NavigateProgress(legIndex = nav.legIndex, legCount = legCount)
        NavigateActions(presentation = presentation, viewModel = viewModel)
    }
}

/**
 * Progress with node dots: one mark per leg (● passed/current, ○ ahead —
 * shape, never hue-only) plus the "Leg i of n" line for screen readers.
 */
@Composable
private fun NavigateProgress(
    legIndex: Int,
    legCount: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth(),
    ) {
        LinearProgressIndicator(
            progress = { if (legCount > 0) (legIndex + 1).toFloat() / legCount else 0f },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (leg in 0 until legCount) {
                Text(
                    text = if (leg <= legIndex) "●" else "○",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (leg <= legIndex) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                )
            }
        }
        Text(
            text = "Leg ${legIndex + 1} of $legCount",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun NavigateOverlayBody(
    nav: NavUi,
    routes: RouteRepository,
    routeId: String,
    online: Boolean,
    legCount: Int,
    researchMode: Boolean,
    viewModel: NavigateViewModel,
    modifier: Modifier = Modifier,
) {
    val camera = rememberCameraRecorder()
    // Baseline honesty in the overlay too: a SENSOR_ONLY trial sees no
    // landmark card, crop, or mask (review 0004 R4).
    val landmark = nav.landmark?.takeIf { shouldShowLandmarkCards(viewModel.baselineMode) }
    val cropBytes = remember(landmark?.cropAssetId) {
        landmark?.cropAssetId?.let { routes.readAsset(routeId, it) }
    }
    val distanceM = maxOf(0.0, nav.distanceToNextM)
    val presentation = navigatePhasePresentation(
        phase = nav.phase,
        hasLandmark = nav.landmark != null,
        isLastLeg = viewModel.isLastLeg(nav),
        showCards = shouldShowLandmarkCards(viewModel.baselineMode),
        researchMode = researchMode,
    )
    CameraNavigateOverlay(
        arrowRad = nav.arrowRad.toFloat(),
        instruction = nav.instruction,
        distanceText = "${oneDecimal(distanceM)} m",
        uncertaintyText = "σ ±${oneDecimal(nav.uncertaintyM)} m",
        offline = !online,
        landmarkTitle = landmark?.title,
        landmarkCue = landmark?.cue,
        landmarkStats = landmark?.let { "in ~${oneDecimal(distanceM)} m" },
        cropBytes = cropBytes,
        cropDescription = landmark?.title,
        maskSvgPath = landmark?.maskSvgPath,
        maskW = landmark?.maskW,
        maskH = landmark?.maskH,
        maskBox = landmark?.box,
        maskFrameW = landmark?.frameW,
        maskFrameH = landmark?.frameH,
        progress01 = nav.progress01.toFloat(),
        legText = "Leg ${nav.legIndex + 1} of $legCount",
        scrimAlpha = overlayScrimAlpha(distanceM),
        // The camera starts only behind the W4-03 permission gate.
        preview = {
            app.simt.echo.platform.CameraPermissionGate {
                camera.Preview(modifier = Modifier.fillMaxSize())
            }
        },
        actions = { NavigateActions(presentation = presentation, viewModel = viewModel) },
        modifier = modifier,
    )
}

@Composable
private fun NavigateActions(
    presentation: NavigatePhasePresentation,
    viewModel: NavigateViewModel,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        // Phase honesty is decided in [navigatePhasePresentation]; here each
        // action only wires to the ViewModel input of the same name. The
        // head of the list is the primary command (never two primaries).
        val primary = presentation.actions.firstOrNull()
        val secondaries = presentation.actions.drop(1)
        if (primary != null) {
            CosmosPrimaryButton(
                label = actionLabel(primary),
                onClick = { viewModel.navigate(primary) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        for (action in secondaries) {
            when (action) {
                NavigateAction.AT_MARKER ->
                    CosmosTertiaryButton(
                        label = actionLabel(action),
                        onClick = { viewModel.navigate(action) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                else -> CosmosSecondaryButton(
                    label = actionLabel(action),
                    onClick = { viewModel.navigate(action) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun actionLabel(action: NavigateAction): String = when (action) {
    NavigateAction.SEE_LANDMARK -> "I see it"
    NavigateAction.NOT_YET -> "Not yet"
    NavigateAction.LOST -> "Lost"
    NavigateAction.GO_BACK_TO_LAST -> "Go back to last"
    NavigateAction.RESTART_FROM_START -> "Restart from start"
    NavigateAction.IM_HERE -> "I'm here"
    NavigateAction.AT_MARKER -> "At marker (trial)"
}

/** Single wiring point from a presented action to its ViewModel input. */
private fun NavigateViewModel.navigate(action: NavigateAction) {
    when (action) {
        NavigateAction.SEE_LANDMARK -> seeLandmark()
        NavigateAction.NOT_YET -> notYet()
        NavigateAction.LOST -> lost()
        NavigateAction.GO_BACK_TO_LAST -> restartFromLast()
        NavigateAction.RESTART_FROM_START -> restartFromStart()
        NavigateAction.IM_HERE -> imHere()
        NavigateAction.AT_MARKER -> atMarker()
    }
}
