package app.simt.echo.ui.shell

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.simt.echo.ui.EchoRoute
import app.simt.echo.ui.atmosphere.CosmosMotionDurations
import app.simt.echo.ui.atmosphere.CosmosMotionTier
import app.simt.echo.ui.atmosphere.EchoField
import app.simt.echo.ui.atmosphere.EchoRippleState
import app.simt.echo.ui.atmosphere.LocalEchoRipples
import app.simt.echo.ui.atmosphere.cosmosMotionTier
import app.simt.echo.ui.brand.EchoMark
import app.simt.echo.ui.components.CosmosIconButton
import app.simt.echo.ui.components.CosmosPrimaryButton
import app.simt.echo.ui.components.CosmosSecondaryButton
import app.simt.echo.ui.icons.EchoIcons
import app.simt.echo.ui.motion.LuminousSprings
import app.simt.echo.ui.theme.EchoExtras

// App shell & navigation grammar (plan W8-05, freeze §9.2 tool-deck/top-chrome
// grammar — the orbital-dock pattern does not apply: ECHO has 2 persistent
// primary actions + Settings, not 4 dock destinations, Cosmos §7.5).
//
// One Cosmos shell owns: top chrome (wordmark, Settings gear, back/close),
// screen transitions (routeChange motif, Cosmos §4.5 durations), safe-area
// clearance, Record + Receive as the persistent primary actions, and heading
// focus on destination change. Every route renders inside the shell; screens
// render no local top bar (titles/back actions were migrated here).

const val EchoShellTag = "EchoShell"
const val EchoShellTopChromeTag = "EchoShellTopChrome"
const val EchoShellWordmarkTag = "EchoShellWordmark"
const val EchoShellHeadingTag = "EchoShellHeading"
const val EchoShellDeckTag = "EchoShellDeck"
const val EchoShellRecordTag = "EchoShell:record"
const val EchoShellReceiveTag = "EchoShell:receive"

/**
 * Top-chrome navigation affordance. Back and Close are never interchanged:
 * Back pops within a flow (hierarchy step), Close dismisses a transient
 * surface (consent/working/offer/scanner/settings overlay). NONE is for
 * the root (Home) and the terminal success (Arrived), where leaving has
 * exactly one dedicated action (persistent deck / Done).
 */
enum class EchoChrome {
    BACK,
    CLOSE,
    NONE,
}

/** Shell configuration for one destination. Pure data; see [shellConfigFor]. */
data class EchoShellConfig(
    val title: String,
    val chrome: EchoChrome,
    val showSettings: Boolean,
    val showPrimaryDeck: Boolean = false,
)

/**
 * Navigation grammar: which chrome, heading, gear and Home action deck each
 * route gets. The gear rides the chrome on collection/task screens only —
 * transient (Processing/Share/Receive), instrument (Align/Navigate) and
 * terminal (Arrived) viewports keep a single job, and Settings never gears to
 * itself. The Record + Receive deck belongs to Home only, and only while
 * Home has content — the empty first-run state carries its own Record and
 * Receive actions in the body, so [homeHasContent] gates the deck.
 */
fun shellConfigFor(route: EchoRoute, homeHasContent: Boolean = true): EchoShellConfig = when (route) {
    is EchoRoute.Home -> EchoShellConfig(
        title = "Home",
        chrome = EchoChrome.NONE,
        showSettings = true,
        // No drafts, saved walks or recoverable drafts: the deck is folded
        // into the empty-state actions and returns with the first walk.
        showPrimaryDeck = homeHasContent,
    )
    is EchoRoute.Record -> EchoShellConfig(title = "Record", chrome = EchoChrome.BACK, showSettings = true)
    is EchoRoute.Review -> EchoShellConfig(title = "Review", chrome = EchoChrome.BACK, showSettings = true)
    is EchoRoute.Processing -> EchoShellConfig(title = "Cloud landmarks", chrome = EchoChrome.CLOSE, showSettings = false)
    is EchoRoute.RouteDetail -> EchoShellConfig(title = "Route", chrome = EchoChrome.BACK, showSettings = true)
    is EchoRoute.Share -> EchoShellConfig(title = "Share", chrome = EchoChrome.CLOSE, showSettings = false)
    is EchoRoute.Receive -> EchoShellConfig(title = "Receive", chrome = EchoChrome.CLOSE, showSettings = false)
    is EchoRoute.Align -> EchoShellConfig(title = "Align", chrome = EchoChrome.BACK, showSettings = false)
    is EchoRoute.Navigate -> EchoShellConfig(title = "Navigate", chrome = EchoChrome.BACK, showSettings = false)
    is EchoRoute.Practice -> EchoShellConfig(title = "Practice", chrome = EchoChrome.BACK, showSettings = false)
    is EchoRoute.Arrived -> EchoShellConfig(title = "Arrived", chrome = EchoChrome.NONE, showSettings = false)
    is EchoRoute.Settings -> EchoShellConfig(title = "Settings", chrome = EchoChrome.CLOSE, showSettings = false)
}

/**
 * Screen-transition duration for the routeChange motif (freeze §7 durations,
 * Cosmos §4.5): FULL settles in the state duration, REDUCED in the feedback
 * duration (opacity only, no travel), STATIC settles immediately.
 */
fun shellTransitionDurationMs(tier: CosmosMotionTier): Int = when (tier) {
    CosmosMotionTier.FULL -> CosmosMotionDurations.STATE_MS
    CosmosMotionTier.REDUCED -> CosmosMotionDurations.FEEDBACK_MS
    CosmosMotionTier.STATIC -> 0
}

/**
 * Route-change motion (W13-02, luminous-freeze §3): FULL travels on the
 * [LuminousSprings.spatialSlow] spring with a spring fade, so a back
 * gesture that interrupts a push retargets instead of jumping. Forward
 * enters from the right; [echoPopEnterTransition] / [echoPopExitTransition]
 * mirror it so going back returns from the left. REDUCED is a short fade
 * with no travel; STATIC settles immediately.
 */
fun echoEnterTransition(tier: CosmosMotionTier): EnterTransition = enter(tier, fromStart = false)

/** Forward exit: the screen being left drifts toward the start edge. */
fun echoExitTransition(tier: CosmosMotionTier): ExitTransition = exit(tier, towardStart = true)

/** Back enter: the previous screen returns from the start edge. */
fun echoPopEnterTransition(tier: CosmosMotionTier): EnterTransition = enter(tier, fromStart = true)

/** Back exit: the dismissed screen leaves toward the end edge. */
fun echoPopExitTransition(tier: CosmosMotionTier): ExitTransition = exit(tier, towardStart = false)

private fun enter(tier: CosmosMotionTier, fromStart: Boolean): EnterTransition = when (tier) {
    CosmosMotionTier.STATIC -> EnterTransition.None
    CosmosMotionTier.REDUCED -> fadeIn(tween(shellTransitionDurationMs(tier)))
    CosmosMotionTier.FULL ->
        fadeIn(LuminousSprings.effectsDefault()) +
            slideInHorizontally(LuminousSprings.spatialSlow()) { if (fromStart) -it / 6 else it / 6 }
}

private fun exit(tier: CosmosMotionTier, towardStart: Boolean): ExitTransition = when (tier) {
    CosmosMotionTier.STATIC -> ExitTransition.None
    CosmosMotionTier.REDUCED -> fadeOut(tween(shellTransitionDurationMs(tier)))
    CosmosMotionTier.FULL ->
        fadeOut(LuminousSprings.effectsDefault()) +
            slideOutHorizontally(LuminousSprings.spatialSlow()) { if (towardStart) -it / 10 else it / 6 }
}

/**
 * The one Cosmos shell. [config] comes from [shellConfigFor] for the current
 * route; [content] is the destination body (NavHost). The Record + Receive
 * deck sits fixed at the bottom of Home only.
 */
@Composable
fun EchoShell(
    config: EchoShellConfig,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onSettings: () -> Unit,
    onRecord: () -> Unit,
    onReceive: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // The echo field (W13-01) and every command that ripples across it
    // share one ripple state per shell.
    val ripples = remember { EchoRippleState() }
    Box(modifier = modifier.fillMaxSize().testTag(EchoShellTag)) {
        EchoField(ripples = ripples)
        CompositionLocalProvider(LocalEchoRipples provides ripples) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopChrome(
                    config = config,
                    onBack = onBack,
                    onClose = onClose,
                    onSettings = onSettings,
                )
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    content()
                }
                if (config.showPrimaryDeck) {
                    PrimaryDeck(
                        onRecord = onRecord,
                        onReceive = onReceive,
                    )
                }
            }
        }
    }
}

@Composable
private fun TopChrome(
    config: EchoShellConfig,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Heading focus (W8-05): the page title takes accessibility focus on
    // every destination change, so TalkBack announces where the user is.
    val focusRequester = remember(config.title) { FocusRequester() }
    LaunchedEffect(config.title) {
        focusRequester.requestFocus()
    }
    var headingFocused by remember { mutableStateOf(false) }
    // Programmatic heading focus is for announcements, not a permanent
    // gold box: the ring appears only while input is keyboard-driven.
    val keyboardInput = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag(EchoShellTopChromeTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when (config.chrome) {
            EchoChrome.BACK -> CosmosIconButton(
                onClick = onBack,
                contentDescription = "Back",
                icon = { Icon(EchoIcons.Back, contentDescription = null) },
            )
            EchoChrome.CLOSE -> CosmosIconButton(
                onClick = onClose,
                contentDescription = "Close",
                icon = { Icon(EchoIcons.Close, contentDescription = null) },
            )
            EchoChrome.NONE -> Unit
        }
        Column(modifier = Modifier.weight(1f)) {
            // Wordmark: the Echo Step mark on its void disc — a miniature
            // of the launcher icon (luminous-freeze §2) — + the "ECHO"
            // label that carries semantics.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EchoMark(markSize = 28.dp, onDisc = true)
                Text(
                    text = "ECHO",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // The eyebrow is decoration: the route title below says
                    // where the user is (review 0013 F9).
                    modifier = Modifier
                        .testTag(EchoShellWordmarkTag)
                        .semantics { invisibleToUser() },
                )
            }
            Text(
                text = config.title,
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier
                    .testTag(EchoShellHeadingTag)
                    .focusRequester(focusRequester)
                    .onFocusChanged { headingFocused = it.isFocused }
                    .focusable()
                    .padding(4.dp)
                    .then(
                        if (headingFocused && keyboardInput) {
                            Modifier.border(
                                2.dp,
                                EchoExtras.gold,
                                MaterialTheme.shapes.small,
                            )
                        } else {
                            Modifier
                        },
                    )
                    .semantics { heading() },
            )
        }
        if (config.showSettings) {
            CosmosIconButton(
                onClick = onSettings,
                contentDescription = "Settings",
                icon = { Icon(EchoIcons.Settings, contentDescription = null) },
            )
        }
    }
}

@Composable
private fun PrimaryDeck(
    onRecord: () -> Unit,
    onReceive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth().testTag(EchoShellDeckTag),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
        ) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Walk it once. Share the way.",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CosmosPrimaryButton(
                        label = "Record",
                        icon = EchoIcons.Record,
                        onClick = onRecord,
                        modifier = Modifier.weight(1f).testTag(EchoShellRecordTag),
                    )
                    CosmosSecondaryButton(
                        label = "Receive",
                        icon = EchoIcons.Receive,
                        onClick = onReceive,
                        modifier = Modifier.weight(1f).testTag(EchoShellReceiveTag),
                    )
                }
            }
        }
    }
}
