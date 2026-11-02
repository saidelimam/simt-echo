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
import app.simt.echo.data.ConfigRepository
import app.simt.echo.data.RouteRepository
import app.simt.echo.data.TrialRepository
import app.simt.echo.model.EchoConfig
import app.simt.echo.model.EchoRoute as SavedRoute
import app.simt.echo.model.shouldShowLandmarkCards
import app.simt.echo.nav.NavUi
import app.simt.echo.platform.CameraRecorder
import app.simt.echo.platform.CaptureScreen
import app.simt.echo.platform.KeepScreenOn
import app.simt.echo.platform.MotionSource
import app.simt.echo.platform.cameraGraphFor
import app.simt.echo.platform.rememberCameraRecorder
import app.simt.echo.platform.rememberCueOutput
import app.simt.echo.platform.rememberHaptics
import app.simt.echo.platform.rememberMotionSource
import app.simt.echo.platform.rememberOnline
import app.simt.echo.ui.components.CosmosBanner
import app.simt.echo.ui.components.CosmosEmptyState
import app.simt.echo.ui.components.StatusChip
import app.simt.echo.ui.components.oneDecimal
import app.simt.echo.ui.copy.MISSING_ROUTE_ERROR
import app.simt.echo.ui.screens.record.RollingDistance
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
    /**
     * Ephemeral route (W13-13 practice tour): handed straight to the
     * ViewModel, never saved — the tour writes neither `routes/` nor
     * `trials/` (null [trials] sink). Null for every saved-route walk.
     */
    ephemeralRoute: SavedRoute? = null,
    /**
     * Where the learned stride is persisted (W12-03). Null for walks that
     * must not touch the stored posterior (the practice tour).
     */
    configs: ConfigRepository? = null,
) {
    val motion = motionSource ?: rememberMotionSource()
    val haptics = rememberHaptics()
    val cues = rememberCueOutput(earconsEnabled = config.earcons)
    val online = rememberOnline()
    KeepScreenOn()
    // Scoped to this navigation back-stack entry, not `remember`: popping
    // the destination must call onCleared() so motion stops and the
    // collectors die with the screen (review 0001 R2).
    val viewModel: NavigateViewModel = viewModel(
        key = routeId,
        factory = viewModelFactory {
            addInitializer(NavigateViewModel::class) {
                NavigateViewModel(routeId, routes, motion, haptics, config, trials, device, baselineMode, configs = configs, cues = cues, onArrived = onArrived, ephemeralRoute = ephemeralRoute)
            }
        },
    )
    val ui by viewModel.ui.collectAsState()
    LaunchedEffect(Unit) {
        viewModel.start()
        viewModel.align()
    }

    val nav = ui
    // Navigate graph (plan W10-03): Preview + Analysis with overlay,
    // Analysis-only otherwise — W12-05 decides when analysis runs at all.
    val camera = rememberCameraRecorder(cameraGraphFor(CaptureScreen.NAVIGATE, config.cameraOverlayEnabled))
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
            camera = camera,
            modifier = modifier,
        )
        return
    }

    // Overlay off: the Analysis-only graph still binds (no visible
    // preview) so the tap runs without a viewfinder.
    camera.Preview(modifier = Modifier)
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
    // W13-08 instrument: pure phase → instrument mapping (all 8 phases,
    // engine-independent). Shared with the camera-HUD body below.
    val landmark = if (showCards) nav.landmark else null
    val instrument = navigateInstrumentFor(nav, landmark != null)
    val distanceM = maxOf(0.0, nav.distanceToNextM)
    val cropBytes = remember(landmark?.cropAssetId) {
        landmark?.cropAssetId?.let { id -> routes.readAsset(routeId, id) }
    }
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(EchoSpace24),
            verticalArrangement = Arrangement.spacedBy(EchoSpace12),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!online) StatusChip(text = "⚠ Offline · sensors only")
                StatusChip(text = instrument.sigmaLabel)
            }
            if (presentation.bannerKind != null) {
                CosmosBanner(message = nav.instruction, kind = presentation.bannerKind)
            }
            // Reticle frame keeps the 200 dp morph arrow legible in motion
            // and outdoors; the instruction Text below carries the
            // semantics. Calm mode (straight walking) dims the frame —
            // text contrast is never reduced.
            Box(
                modifier = Modifier
                    .border(
                        2.dp,
                        if (instrument.calmMode) {
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        RoundedCornerShape(24.dp),
                    )
                    .padding(8.dp)
                    .testTag(NavigateReticleTag),
            ) {
                InstrumentArrow(instrument = instrument, arrowRad = nav.arrowRad)
            }
            AnimatedContent(targetState = nav.instruction, label = "instruction") { instruction ->
                Text(
                    text = instruction,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
            }
            RollingDistance(text = "${oneDecimal(distanceM)} m")
            // Baseline (SENSOR_ONLY) runs show no landmark cards: turns and
            // distance only, so the comparison against ECHO stays honest.
            // Generic enter/exit only — no per-screen animation system.
            AnimatedVisibility(
                visible = landmark != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
                label = "landmark",
            ) {
                landmark?.let {
                    InstrumentLandmarkCard(
                        showLookForThis = instrument.showLookForThis,
                        title = it.title,
                        cue = it.cue,
                        stats = "in ~${oneDecimal(distanceM)} m",
                        cropBytes = cropBytes,
                        contentDescription = it.title,
                        maskSvgPath = it.maskSvgPath,
                        maskW = it.maskW,
                        maskH = it.maskH,
                        box = it.box,
                        frameW = it.frameW,
                        frameH = it.frameH,
                    )
                }
            }
            // Fingerprint suggestion (W12-05): the chip asks, never asserts.
            InstrumentBelowDistance(
                chipCopy = instrument.fingerprintChipCopy,
                onConfirm = viewModel::confirmFingerprint,
                onDismiss = viewModel::dismissFingerprint,
            )
            NavigateProgress(legIndex = nav.legIndex, legCount = legCount)
            NavigateActions(presentation = presentation, viewModel = viewModel)
        }
        if (instrument.glowSide != null) {
            NavigatePeripheryGlow(
                side = instrument.glowSide,
                intensity01 = instrument.glowIntensity01,
                modifier = Modifier.fillMaxSize(),
            )
        }
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

