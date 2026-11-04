package app.simt.echo.ui.screens.record

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
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
import app.simt.echo.capture.CaptureHud
import app.simt.echo.data.DraftRepository
import app.simt.echo.model.EchoConfig
import app.simt.echo.platform.CameraPermissionGate
import app.simt.echo.platform.CaptureScreen
import app.simt.echo.platform.KeepScreenOn
import app.simt.echo.platform.cameraGraphFor
import app.simt.echo.platform.currentEpochMs
import app.simt.echo.platform.rememberCameraRecorder
import app.simt.echo.platform.rememberCueOutput
import app.simt.echo.platform.rememberHaptics
import app.simt.echo.platform.rememberMotionSource
import app.simt.echo.transfer.WalkStagerFactory
import app.simt.echo.ui.components.CosmosBanner
import app.simt.echo.ui.components.CosmosBannerKind
import app.simt.echo.ui.components.CosmosPrimaryButton
import app.simt.echo.ui.components.CosmosRecordButton
import app.simt.echo.ui.components.CosmosSecondaryButton
import app.simt.echo.ui.components.CosmosSheet
import app.simt.echo.ui.components.CosmosTextField
import app.simt.echo.ui.components.StatusChip
import app.simt.echo.ui.components.oneDecimal
import app.simt.echo.ui.icons.EchoIcons
import app.simt.echo.ui.theme.EchoExtras
import app.simt.echo.ui.theme.EchoTouchOther
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.math.PI
import kotlin.random.Random

const val RecordInstrumentTag = "RecordInstrument"
const val RecordPrestartTag = "RecordPrestart"
const val RecordHeadingTag = "RecordHeading"
const val RecordOptInTag = "RecordOptIn"

/** Live-path panel width: a compact instrument, not a full-width band. */
private val TrailPanelWidth = 150.dp

/** Compact panel height: a corner instrument, well clear of the action row. */
private val TrailPanelHeight = 112.dp

/** End inset: the same 20 dp breathing room the panel keeps from the corner. */
private val TrailPanelEnd = 20.dp

/**
 * Record screen (plan §8.3 / W8-07): personal-orrery grammar (Cosmos §6.3).
 * The camera is the instrument and owns the first read — the preview fills
 * the viewport and the HUD overlays it. Pre-start overlay, top chips
 * (time/steps/marks), giant tabular distance, heading tick (the missing V2
 * §8.3 element), 88 dp Start, Mark spot, long-press Finish — then a real
 * Cosmos Finish sheet via the shared canopy surface (no dialog bypass).
 *
 * Presentation + handoff only: [CaptureSession] behaviour is untouched, and
 * the heading below is a pure display read of the existing motion flow —
 * no geometry input changes.
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
    /** Upload while walking (W11-09); null builds a screen that stages nothing. */
    walkStagers: WalkStagerFactory = WalkStagerFactory { _, _, _ -> null },
    /**
     * Leaves the screen (host: back to Home). Wired to the finish sheet's
     * dismiss so system back can never strand the walk on a dead form: the
     * stopped draft stays on disk and Home lists it under interrupted drafts.
     */
    onAbandon: () -> Unit = {},
) {
    val motion = rememberMotionSource()
    // Record graph: Preview + Video + Analysis (plan W10-03); the bind
    // ladder reports its ledger mode into every capture.json.
    val camera = rememberCameraRecorder(cameraGraphFor(CaptureScreen.RECORD))
    val haptics = rememberHaptics()
    // Sound cues (W13-09): the same opt-in output Navigate uses. Recording had
    // no sonic channel at all, so the Settings switch could be on and a walk
    // still stayed silent.
    val cues = rememberCueOutput(earconsEnabled = config.earcons)
    val session = remember(motion, camera, haptics, drafts, config, resumeDraftId) {
        CaptureSession(
            motion = motion,
            camera = camera,
            haptics = haptics,
            cues = cues,
            drafts = drafts,
            scope = CoroutineScope(Dispatchers.Default),
            nowMs = { currentEpochMs() },
            newDraftId = { "draft-${Random.nextInt(1_000_000)}" },
            config = config,
            perceptionMode = { camera.perceptionMode },
        )
    }
    // Back-stack-scoped (review 0001 pattern): rotation/process handling
    // must not silently drop the session behind a `remember`.
    val viewModel: RecordViewModel = viewModel(
        key = resumeDraftId ?: "new",
        factory = viewModelFactory {
            addInitializer(RecordViewModel::class) {
                RecordViewModel(session, mobileDataAllowed = config.readyUploadMobileData, walkStagers = walkStagers)
            }
        },
    )
    val uiState by viewModel.uiState.collectAsState()
    val readyOptIn by viewModel.readyOptIn.collectAsState()
    LaunchedEffect(Unit) { viewModel.prepare(resumeDraftId) }
    KeepScreenOn()

    // Heading is presentation-only: a second collector on the shared motion
    // flow (PDR engine multicasts; CaptureSession's own collection is
    // unaffected). Null until the first sample — the tick below keeps its
    // slot and shows a placeholder, so unavailable/denied/stale sensors
    // never move the geometry.
    var headingRad by remember { mutableStateOf<Double?>(null) }
    // Echo-walk overlay state (W13-04): presentation-only reader of the
    // same motion flow (PDR multicasts) plus hud.marks; CaptureSession's
    // own collection is unaffected. Snapshot refreshes are event-rate.
    val walkTracker = remember { EchoWalkTracker() }
    var walk by remember { mutableStateOf(EchoWalkSnapshot()) }
    LaunchedEffect(motion) {
        motion.updates.collect {
            headingRad = it.yawRad
            walkTracker.onMotion(it)
            walk = walkTracker.snapshot()
        }
    }
    LaunchedEffect(session) {
        walkTracker.collectLedgerOffers(session.ledgerOffers) { walk = walkTracker.snapshot() }
    }
    val markCount = uiState.hud.marks
    LaunchedEffect(markCount) {
        walkTracker.onMarks(markCount)
        walk = walkTracker.snapshot()
    }

    CameraPermissionGate {
        RecordInstrument(
            state = uiState.state,
            hud = uiState.hud,
            headingRad = headingRad,
            error = uiState.error,
            walk = walk,
            onHighlightExpired = {
                walkTracker.clearHighlight()
                walk = walkTracker.snapshot()
            },
            onStart = viewModel::requestStart,
            onMark = viewModel::mark,
            onBeginFinish = viewModel::beginFinish,
            onSave = { name, destination ->
                viewModel.finish(name, destination, onSaved)
            },
            onAbandon = onAbandon,
            readyOptIn = readyOptIn,
            onOptInChange = viewModel::setReadyOptIn,
            onGrantConsent = viewModel::grantReadyConsent,
            onDismissConsent = viewModel::dismissReadyConsent,
            onDeclineConsent = viewModel::declineReadyConsent,
            modifier = modifier,
            preview = {
                camera.Preview(modifier = Modifier.fillMaxSize(), onError = viewModel::onCameraError)
            },
        )
    }
}

/**
 * Pure instrument body: the same geometry in every state (chips row,
 * distance + heading, action zone), so tests and TalkBack meet one layout.
 * Split from [RecordScreen] so host tests drive the whole journey without
 * platform sensors or the camera.
 */
@Composable
internal fun RecordInstrument(
    state: CaptureState,
    hud: CaptureHud,
    headingRad: Double?,
    error: String?,
    onStart: () -> Unit,
    onMark: () -> Unit,
    onBeginFinish: () -> Unit,
    onSave: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    /** Leaves the screen when the finish form is dismissed; never a no-op in production. */
    onAbandon: () -> Unit = {},
    walk: EchoWalkSnapshot = EchoWalkSnapshot(),
    onHighlightExpired: () -> Unit = {},
    preview: @Composable () -> Unit = {},
    readyOptIn: ReadyOptInUiState = ReadyOptInUiState(),
    onOptInChange: (Boolean) -> Unit = {},
    onGrantConsent: () -> Unit = {},
    onDismissConsent: () -> Unit = {},
    onDeclineConsent: () -> Unit = {},
) {
    Box(modifier = modifier.fillMaxSize().testTag(RecordInstrumentTag)) {
        preview()
        // Echo-walk overlays (W13-04): behind the instrument column so the
        // action zone stays on top; none consume input or semantics beyond
        // their documented tags.
        StepRipples(ripples = walk.ripples)
        LedgerBrackets(highlight = walk.highlight, onExpired = onHighlightExpired)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Top HUD group: chips, then the live path only while recording.
            // The panel used to float over the preview at a fixed 112 dp
            // inset, so at fontScale 2.0 it collided with the chips and the
            // banner (review 0013 F5).
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusChip(text = formatElapsed(hud.elapsedMs))
                    StatusChip(text = "${hud.steps} steps")
                    StatusChip(text = "${hud.marks} spots")
                }
                if (state == CaptureState.RECORDING) {
                    TrailRibbon(
                        trail = walk.trail,
                        headYaw = headingRad,
                        height = TrailPanelHeight,
                        modifier = Modifier
                            .align(Alignment.End)
                            .padding(end = TrailPanelEnd)
                            .width(TrailPanelWidth)
                            .clip(MaterialTheme.shapes.large)
                            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f))
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                error?.let { message ->
                    CosmosBanner(message = message, kind = CosmosBannerKind.Error)
                }
                RollingDistance(text = "${oneDecimal(hud.distanceM)} m")
                HeadingTick(yawRad = headingRad)
                when (state) {
                    CaptureState.READY, CaptureState.IDLE -> PrestartOverlay(
                        onStart = onStart,
                        optIn = readyOptIn.optIn,
                        optInLabel = readyOptIn.optInLabel,
                        onOptInChange = onOptInChange,
                    )
                    CaptureState.RECORDING -> {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            CosmosPrimaryButton(
                                label = "Mark spot",
                                icon = EchoIcons.Record,
                                onClick = onMark,
                                modifier = Modifier.weight(1f),
                            )
                            FinishButton(
                                onFinish = onBeginFinish,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    CaptureState.FINISHING, CaptureState.SAVED -> Unit
                }
            }
        }
    }
    if (state == CaptureState.FINISHING || state == CaptureState.SAVED) {
        FinishSheet(onSave = onSave, onDismiss = onAbandon)
    }
    if (readyOptIn.consentPending) {
        ReadyConsentSheet(
            onGrant = onGrantConsent,
            body = readyOptIn.consentBody,
            onDismiss = onDismissConsent,
            onDecline = onDeclineConsent,
        )
    }
}

/**
 * Pre-start overlay: one centred task on the live preview, one primary
 * command. The instrument (camera) stays the first read behind it.
 *
 * W11-09: the ready-on-arrival opt-in rides here, default off. Ticking
 * it does not start anything — Start routes through the consent sheet
 * first ([ReadyConsentSheet]), so consent always precedes recording.
 */
@Composable
private fun PrestartOverlay(
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
    optIn: Boolean = false,
    optInLabel: String = ReadyOptInUiState().optInLabel,
    onOptInChange: (Boolean) -> Unit = {},
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.testTag(RecordPrestartTag),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Text(
                text = "Stand at the start",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = "Point at your start.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .testTag(RecordOptInTag)
                    .toggleable(
                        value = optIn,
                        onValueChange = onOptInChange,
                        role = Role.Checkbox,
                    ),
            ) {
                Checkbox(
                    checked = optIn,
                    onCheckedChange = null,
                )
                Text(
                    text = optInLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            CosmosRecordButton(label = "Start", onClick = onStart)
        }
    }
}

/**
 * Ready-on-arrival consent sheet (plan W11-09) on the shared canopy
 * surface: appears *before* Start, never after. Allow records consent
 * and starts recording; Not now starts without upload; dismiss returns
 * to the overlay with recording never started.
 */
@Composable
internal fun ReadyConsentSheet(
    onGrant: () -> Unit,
    onDismiss: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
    body: String = ReadyOptInUiState().consentBody,
) {
    CosmosSheet(
        title = "Find landmarks while I walk",
        onDismiss = onDismiss,
        primaryLabel = "Allow",
        onPrimary = onGrant,
        modifier = modifier,
        content = {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CosmosSecondaryButton(label = "Not now", onClick = onDecline)
        },
    )
}

/**
 * Pure heading display (V2 §8.3): degrees + 8-wind point from the existing
 * motion yaw. Display only — never an input to capture geometry.
 * [yawRad] null (no sample yet: unavailable/denied/stale sensor) renders
 * the same node with a placeholder so the layout never shifts.
 */
@Composable
internal fun HeadingTick(
    yawRad: Double?,
    modifier: Modifier = Modifier,
) {
    val text = if (yawRad == null) "—" else formatHeadingTick(yawRad)
    val description = if (yawRad == null) {
        "Heading unavailable"
    } else {
        "Heading $text"
    }
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium.copy(
            fontFeatureSettings = "tnum",
        ),
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        modifier = modifier
            .testTag(RecordHeadingTag)
            .semantics { contentDescription = description },
    )
}

private val COMPASS_8 = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

/** Degrees clockwise from north + 8-wind point, e.g. "90° E". */
internal fun formatHeadingTick(yawRad: Double): String {
    val deg = ((yawRad * 180.0 / PI) % 360.0 + 360.0) % 360.0
    val whole = ((deg + 0.5).toInt()) % 360
    val point = COMPASS_8[((deg + 22.5) / 45.0).toInt() % 8]
    return "$whole° $point"
}

/**
 * Finish sheet on the shared canopy surface: route name + destination,
 * one Continue.
 *
 * Back and scrim both route to [onDismiss], which leaves the screen. It
 * used to be a deliberate no-op, and because a modal sheet owns the back
 * gesture that made system back dead: the walk was stopped, the form was
 * the only forward path, and nothing could dismiss it — the screen was
 * stuck. Leaving is the honest exit: the stopped draft is already on disk,
 * so Home lists it under interrupted drafts and the walk is recoverable.
 */
@Composable
internal fun FinishSheet(
    onSave: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit = {},
) {
    var name by remember { mutableStateOf("") }
    var destination by remember { mutableStateOf("") }
    CosmosSheet(
        title = "Name this walk",
        onDismiss = onDismiss,
        primaryLabel = "Continue",
        onPrimary = { onSave(name, destination) },
        modifier = modifier,
        content = {
            CosmosTextField(
                label = "Route name",
                value = name,
                onValueChange = { name = it },
            )
            CosmosTextField(
                label = "Destination label",
                value = destination,
                onValueChange = { destination = it },
            )
        },
    )
}

/**
 * Long-press (≥ 600 ms is the gesture recognizer's long-press) to finish.
 * 48 dp target with a Button role; the semantics onClick gives TalkBack a
 * standard activation path, while sighted touch users keep the gesture.
 */
@Composable
private fun FinishButton(
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pressed by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    // A normal tap used to do nothing at all. It now says what the gesture
    // is, while the long press remains the action (review 0013 F5).
    var hint by remember { mutableStateOf(false) }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (pressed) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        border = BorderStroke(
            width = if (focused) 2.dp else 1.5.dp,
            color = if (focused) EchoExtras.gold else MaterialTheme.colorScheme.error,
        ),
        modifier = modifier
            .sizeIn(minWidth = EchoTouchOther, minHeight = EchoTouchOther)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = "Hold to finish"
                onClick { onFinish(); true }
                onLongClick { onFinish(); true }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { hint = true },
                    onLongPress = {
                        pressed = false
                        onFinish()
                    },
                )
            },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text(
                text = "Hold to finish",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error,
            )
            if (hint) {
                Text(
                    text = "Hold for a second to finish",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

internal fun formatElapsed(ms: Long): String {
    val totalSeconds = ms / 1000
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}
