package app.simt.echo.ui.shell

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.simt.echo.ui.EchoRoute
import app.simt.echo.ui.atmosphere.CosmosBackground
import app.simt.echo.ui.atmosphere.CosmosMotionDurations
import app.simt.echo.ui.atmosphere.CosmosMotionEasings
import app.simt.echo.ui.atmosphere.CosmosMotionTier
import app.simt.echo.ui.atmosphere.CosmosVariant
import app.simt.echo.ui.atmosphere.cosmosMotionTier
import app.simt.echo.ui.brand.EchoMark
import app.simt.echo.ui.components.CosmosIconButton
import app.simt.echo.ui.components.CosmosSecondaryButton

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
)

/**
 * Navigation grammar: which chrome, heading and gear each route gets.
 * The gear rides the chrome on collection/task screens only — transient
 * (Processing/Share/Receive), instrument (Align/Navigate) and terminal
 * (Arrived) viewports keep a single job, and Settings never gears to
 * itself.
 */
fun shellConfigFor(route: EchoRoute): EchoShellConfig = when (route) {
    is EchoRoute.Home -> EchoShellConfig(title = "Echo", chrome = EchoChrome.NONE, showSettings = true)
    is EchoRoute.Record -> EchoShellConfig(title = "Record", chrome = EchoChrome.BACK, showSettings = true)
    is EchoRoute.Review -> EchoShellConfig(title = "Review", chrome = EchoChrome.BACK, showSettings = true)
    is EchoRoute.Processing -> EchoShellConfig(title = "Cloud landmarks", chrome = EchoChrome.CLOSE, showSettings = false)
    is EchoRoute.RouteDetail -> EchoShellConfig(title = "Route", chrome = EchoChrome.BACK, showSettings = true)
    is EchoRoute.Share -> EchoShellConfig(title = "Share", chrome = EchoChrome.CLOSE, showSettings = false)
    is EchoRoute.Receive -> EchoShellConfig(title = "Receive", chrome = EchoChrome.CLOSE, showSettings = false)
    is EchoRoute.Align -> EchoShellConfig(title = "Align", chrome = EchoChrome.BACK, showSettings = false)
    is EchoRoute.Navigate -> EchoShellConfig(title = "Navigate", chrome = EchoChrome.BACK, showSettings = false)
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

/** Route-change enter: fade (+ short horizontal travel on FULL only). */
fun echoEnterTransition(tier: CosmosMotionTier): EnterTransition {
    val ms = shellTransitionDurationMs(tier)
    if (ms <= 0) return EnterTransition.None
    return if (tier == CosmosMotionTier.FULL) {
        fadeIn(tween(ms, easing = CosmosMotionEasings.Enter)) +
            slideInHorizontally(tween(ms, easing = CosmosMotionEasings.Enter)) { it / 8 }
    } else {
        fadeIn(tween(ms))
    }
}

/** Route-change exit: mirror of [echoEnterTransition]. */
fun echoExitTransition(tier: CosmosMotionTier): ExitTransition {
    val ms = shellTransitionDurationMs(tier)
    if (ms <= 0) return ExitTransition.None
    return if (tier == CosmosMotionTier.FULL) {
        fadeOut(tween(ms, easing = CosmosMotionEasings.Exit)) +
            slideOutHorizontally(tween(ms, easing = CosmosMotionEasings.Exit)) { it / 8 }
    } else {
        fadeOut(tween(ms))
    }
}

/**
 * The one Cosmos shell. [config] comes from [shellConfigFor] for the current
 * route; [content] is the destination body (NavHost). The deck pair are
 * equal persistent actions (secondary commands, never a second primary —
 * W8-04 one-primary-per-region rule); each disables on its own route so the
 * shell never pushes a duplicate destination.
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
    recordEnabled: Boolean = true,
    receiveEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier.fillMaxSize().testTag(EchoShellTag)) {
        CosmosBackground(variant = CosmosVariant.Panel)
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
            PrimaryDeck(
                onRecord = onRecord,
                onReceive = onReceive,
                recordEnabled = recordEnabled,
                receiveEnabled = receiveEnabled,
            )
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
                icon = {
                    Text(
                        text = "←",
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
            )
            EchoChrome.CLOSE -> CosmosIconButton(
                onClick = onClose,
                contentDescription = "Close",
                icon = {
                    Text(
                        text = "×",
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
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
                    modifier = Modifier.testTag(EchoShellWordmarkTag),
                )
            }
            Text(
                text = config.title,
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier
                    .testTag(EchoShellHeadingTag)
                    .focusRequester(focusRequester)
                    .focusable()
                    .semantics { heading() },
            )
        }
        if (config.showSettings) {
            CosmosIconButton(
                onClick = onSettings,
                contentDescription = "Settings",
                icon = {
                    Text(
                        text = "⚙",
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
            )
        }
    }
}

@Composable
private fun PrimaryDeck(
    onRecord: () -> Unit,
    onReceive: () -> Unit,
    recordEnabled: Boolean,
    receiveEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag(EchoShellDeckTag),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CosmosSecondaryButton(
            label = "Record",
            onClick = onRecord,
            enabled = recordEnabled,
            modifier = Modifier.weight(1f).testTag(EchoShellRecordTag),
        )
        CosmosSecondaryButton(
            label = "Receive",
            onClick = onReceive,
            enabled = receiveEnabled,
            modifier = Modifier.weight(1f).testTag(EchoShellReceiveTag),
        )
    }
}
