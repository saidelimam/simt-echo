package app.simt.echo.ui.screens.navigate

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import app.simt.echo.data.RouteRepository
import app.simt.echo.model.shouldShowLandmarkCards
import app.simt.echo.nav.NavUi
import app.simt.echo.platform.CameraRecorder
import app.simt.echo.ui.components.CameraNavigateOverlay
import app.simt.echo.ui.components.oneDecimal
import app.simt.echo.ui.components.overlayScrimAlpha
import app.simt.echo.ui.screens.record.RollingDistance

@Composable
internal fun NavigateOverlayBody(
    nav: NavUi,
    routes: RouteRepository,
    routeId: String,
    online: Boolean,
    legCount: Int,
    researchMode: Boolean,
    viewModel: NavigateViewModel,
    camera: CameraRecorder,
    modifier: Modifier = Modifier,
) {
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
    // W13-08 instrument over the camera HUD (same pure mapping as the
    // non-camera body; light text over the dark scrim in BOTH schemes).
    val instrument = navigateInstrumentFor(nav, landmark != null)
    val lime = androidx.compose.ui.graphics.Color(0xFFC6F432).copy(alpha = 0.92f)
    val white = androidx.compose.ui.graphics.Color.White
    Box(modifier = modifier.fillMaxSize()) {
        CameraNavigateOverlay(
            arrowRad = nav.arrowRad.toFloat(),
            instruction = nav.instruction,
            distanceText = "${oneDecimal(distanceM)} m",
            uncertaintyText = instrument.sigmaLabel,
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
            arrow = {
                InstrumentArrow(instrument = instrument, arrowRad = nav.arrowRad, color = lime)
            },
            distance = {
                RollingDistance(text = "${oneDecimal(distanceM)} m", color = white)
            },
            landmarkCard = {
                if (landmark != null) {
                    InstrumentLandmarkCard(
                        showLookForThis = instrument.showLookForThis,
                        title = landmark.title,
                        cue = landmark.cue,
                        stats = "in ~${oneDecimal(distanceM)} m",
                        cropBytes = cropBytes,
                        contentDescription = landmark.title,
                        maskSvgPath = landmark.maskSvgPath,
                        maskW = landmark.maskW,
                        maskH = landmark.maskH,
                        box = landmark.box,
                        frameW = landmark.frameW,
                        frameH = landmark.frameH,
                    )
                }
            },
            belowDistance = {
                InstrumentBelowDistance(
                    chipCopy = instrument.fingerprintChipCopy,
                    onConfirm = viewModel::confirmFingerprint,
                    onDismiss = viewModel::dismissFingerprint,
                )
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (instrument.glowSide != null) {
            NavigatePeripheryGlow(
                side = instrument.glowSide,
                intensity01 = instrument.glowIntensity01,
                color = lime,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
