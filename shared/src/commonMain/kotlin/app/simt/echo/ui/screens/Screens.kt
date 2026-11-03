package app.simt.echo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.simt.echo.data.RecoverableDraft
import app.simt.echo.data.destination
import app.simt.echo.model.EchoRoute as SavedRoute
import app.simt.echo.model.NodeType
import app.simt.echo.ui.EchoRoute
import app.simt.echo.ui.brand.EchoMark
import app.simt.echo.ui.components.CosmosEmptyState
import app.simt.echo.ui.components.CosmosRecordRow
import app.simt.echo.ui.components.CosmosRecordRows
import app.simt.echo.ui.components.oneDecimal
import app.simt.echo.ui.copy.UNTITLED_WALK
import app.simt.echo.ui.motion.echoSharedElement
import app.simt.echo.ui.motion.revealOnEnter
import app.simt.echo.ui.motion.sigilSharedKey
import app.simt.echo.ui.screens.navigate.PRACTICE_OFFER_LABEL
import app.simt.echo.ui.sigil.RouteSigil
import app.simt.echo.ui.sigil.routeSigil

/** Below this height the first-run hero shrinks so "Record a walk" stays above the fold. */
private val TALL_HOME_MIN_HEIGHT = 560.dp

/**
 * Home in Cosmos (plan W8-06, collections grammar §6.5), alive in
 * Luminous (W13-11).
 *
 * Saved walks are *records* led by the route's own sigil (the same glyph
 * flies to RouteDetail as a shared element) — name, length, turns,
 * landmark count, an Own/Received/Draft badge (shape + text, never
 * hue-only) and the offline-ready mark — stacked in stable tracks (line
 * dividers, never nested cards) that reveal one after another on entry.
 * Interrupted drafts recover first when present (W4-03) and are not
 * repeated in Drafts: the two sections partition one set of drafts.
 *
 * Each section is one band on a soft vertical wash that fades into the
 * void — the wash is the division, so no outlines and no nested cards: an
 * accented eyebrow + a lifted-to-transparent gradient reads the grouping
 * at a glance without a border anywhere.
 *
 * First run is
 * Ignition: the Echo Step mark breathing its idle echo above one centred
 * task and one primary command.
 *
 * Presentation only — the shell owns the top chrome and the persistent
 * Record/Receive deck (W8-05); ViewModel/state shapes untouched.
 */
@Composable
fun HomeScreen(
    onOpen: (EchoRoute) -> Unit,
    modifier: Modifier = Modifier,
    draftIds: List<String> = emptyList(),
    /** Display name per draft id (route name, else destination); blanks fall back, never raw ids. */
    draftNames: Map<String, String> = emptyMap(),
    onOpenDraft: (String) -> Unit = { onOpen(EchoRoute.Review(it)) },
    savedRoutes: List<SavedRoute> = emptyList(),
    onOpenRoute: (String) -> Unit = { onOpen(EchoRoute.RouteDetail(it)) },
    recoverableDrafts: List<RecoverableDraft> = emptyList(),
    onResumeDraft: (RecoverableDraft) -> Unit = { onOpen(it.destination()) },
    /**
     * Guided practice tour offer (W13-13): first run only, labelled
     * Practice, never a saved-route row — the tour walk is bundled code,
     * not a listed route.
     */
    onPractice: () -> Unit = { onOpen(EchoRoute.Practice) },
) {
    // A kill mid-capture leaves both a draft directory and a capture, so an
    // interrupted draft arrives here twice: as recoverable and as a plain
    // draft. The recovery section leads with it, so it must not repeat below
    // — one row, one tap target (W4-03).
    val interruptedIds = recoverableDrafts.mapTo(mutableSetOf()) { it.draftId }
    val remainingDraftIds = draftIds.filterNot { it in interruptedIds }
    if (savedRoutes.isEmpty() && remainingDraftIds.isEmpty() && recoverableDrafts.isEmpty()) {
        // First-run Ignition: one centred task, one primary command, with
        // camera permission priming (W9-11) in the body. Presentation only:
        // nothing requests permission from here; the Record/Receive gates
        // ask on entry, and nothing leaves the phone until the user shares.
        // The hero yields to the task: on short screens it shrinks so the
        // one command stays above the fold; at large font scales the
        // whole state scrolls rather than clipping.
        BoxWithConstraints(modifier = modifier.fillMaxSize()) {
            val tall = maxHeight >= TALL_HOME_MIN_HEIGHT
            CosmosEmptyState(
                title = "No walks saved yet",
                body = "Record a short walk to share it as a guided route. " +
                    "Recording and scanning use the camera — nothing leaves " +
                    "this phone until you share.",
                actionLabel = "Record a walk",
                onAction = { onOpen(EchoRoute.Record()) },
                // First-run Practice offer: replays the bundled fixture
                // walk through the real Navigate instrument. Secondary —
                // recording stays the one primary command — and labelled
                // Practice, never listed among saved walks.
                secondaryLabel = PRACTICE_OFFER_LABEL,
                onSecondary = onPractice,
                hero = {
                    EchoMark(markSize = if (tall) 116.dp else 64.dp, onDisc = true, idleEcho = true)
                },
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = if (tall) 16.dp else 0.dp),
            )
        }
        return
    }
    var index = 0
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (recoverableDrafts.isNotEmpty()) {
            // Interrupted work first: a kill mid-record or mid-upload
            // must greet the user on relaunch, not hide.
            HomeSection(
                title = "Interrupted drafts",
                headingModifier = Modifier.revealOnEnter(index++),
                // The one section the user is expected to act on first:
                // a faint ember wash, meaning carried by its heading text.
                emphasis = true,
                rows = recoverableDrafts.map { draft ->
                    val at = index++
                    {
                        CosmosRecordRow(
                            title = draftDisplayName(draft.draftId, draftNames),
                            subtitle = if (draft.hasRouteDraft) {
                                "Ready to review"
                            } else {
                                "${draft.sampleCount} samples · Resume recording"
                            },
                            badges = listOf("Draft"),
                            onClick = { onResumeDraft(draft) },
                            modifier = Modifier.revealOnEnter(at),
                        )
                    }
                },
            )
        }
        if (savedRoutes.isNotEmpty()) {
            HomeSection(
                title = "Saved walks",
                headingModifier = Modifier.revealOnEnter(index++),
                rows = savedRoutes.map { route ->
                    val at = index++
                    {
                        val turns = route.nodes.count { it.type == NodeType.TURN }
                        // Own saves stamp the Segmenter default; anything
                        // else arrived over the wire (W2-04). Every saved
                        // walk lives on this phone, so all are offline-ready.
                        val provenance = if (route.creatorLabel == "creator") "Own" else "Received"
                        val sigil = remember(route) { routeSigil(route) }
                        CosmosRecordRow(
                            title = route.name.ifEmpty { UNTITLED_WALK },
                            subtitle = "${oneDecimal(route.totalLengthM)} m · " +
                                "$turns turns · ${route.landmarks.size} landmarks",
                            badges = listOf(provenance, "Offline-ready"),
                            onClick = { onOpenRoute(route.routeId) },
                            leading = {
                                RouteSigil(
                                    geometry = sigil,
                                    sigilSize = 56.dp,
                                    modifier = Modifier.echoSharedElement(sigilSharedKey(route.routeId)),
                                )
                            },
                            modifier = Modifier.revealOnEnter(at),
                        )
                    }
                },
            )
        }
        if (remainingDraftIds.isNotEmpty()) {
            HomeSection(
                title = "Drafts",
                headingModifier = Modifier.revealOnEnter(index++),
                rows = remainingDraftIds.map { draftId ->
                    val at = index++
                    {
                        CosmosRecordRow(
                            title = draftDisplayName(draftId, draftNames),
                            subtitle = "Unfinished walk",
                            badges = listOf("Draft"),
                            onClick = { onOpenDraft(draftId) },
                            modifier = Modifier.revealOnEnter(at),
                        )
                    }
                },
            )
        }
    }
}

/**
 * One Home section: its records on a soft vertical wash that fades into the
 * void. The wash *is* the division — no outline, no nested card — so bands
 * separate by light and air rather than by a border. [emphasis] tints the
 * band the user should act on first with the primary channel at low alpha;
 * the heading text carries the meaning, the hue only reinforces it.
 */
@Composable
private fun HomeSection(
    title: String,
    rows: List<@Composable () -> Unit>,
    headingModifier: Modifier = Modifier,
    emphasis: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val wash = if (emphasis) {
        scheme.primary.copy(alpha = 0.12f)
    } else {
        scheme.surfaceContainer.copy(alpha = 0.55f)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            // Fade to the wash's own transparent, never to plain
            // Color.Transparent: interpolating toward transparent black
            // would smear grey through the band.
            .background(Brush.verticalGradient(listOf(wash, wash.copy(alpha = 0f))))
            .padding(top = 12.dp, bottom = 6.dp),
    ) {
        SectionHeading(title, headingModifier, emphasis)
        CosmosRecordRows(rows = rows)
    }
}

@Composable
private fun SectionHeading(text: String, modifier: Modifier = Modifier, emphasis: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
    ) {
        // Decorative eyebrow: the heading text is what gets announced.
        Box(
            modifier = Modifier
                .size(width = 3.dp, height = 14.dp)
                .clip(CircleShape)
                .background(
                    if (emphasis) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                    },
                )
                .clearAndSetSemantics {},
        )
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = if (emphasis) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.semantics { heading() },
        )
    }
}

/** Draft display name: the route/draft name when one exists, never a raw id. */
private fun draftDisplayName(draftId: String, names: Map<String, String>): String =
    names[draftId]?.takeIf { it.isNotBlank() } ?: "Untitled draft"
