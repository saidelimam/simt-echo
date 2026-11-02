package app.simt.echo.ui

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.simt.echo.AppGraph
import app.simt.echo.capture.ReplayMotionSource
import app.simt.echo.capture.captureLogForRoute
import app.simt.echo.data.DraftRepository
import app.simt.echo.data.listRecoverable
import app.simt.echo.platform.currentEpochMs
import app.simt.echo.platform.rememberFileSharing
import app.simt.echo.platform.rememberHaptics
import app.simt.echo.platform.rememberMediaTools
import app.simt.echo.platform.rememberMotionSource
import app.simt.echo.ui.atmosphere.cosmosMotionTier
import app.simt.echo.ui.motion.LocalEchoDestinationScope
import app.simt.echo.ui.motion.LocalEchoSharedScope
import app.simt.echo.ui.screens.HomeScreen
import app.simt.echo.ui.screens.align.AlignScreen
import app.simt.echo.ui.screens.arrived.ArrivedScreen
import app.simt.echo.ui.screens.detail.RouteDetailScreen
import app.simt.echo.ui.screens.navigate.NavigateScreen
import app.simt.echo.ui.screens.processing.ProcessingScreen
import app.simt.echo.ui.screens.processing.ProcessingViewModel
import app.simt.echo.ui.screens.receive.ReceiveScreen
import app.simt.echo.ui.screens.record.RecordScreen
import app.simt.echo.ui.screens.review.ReviewScreen
import app.simt.echo.ui.screens.review.ReviewViewModel
import app.simt.echo.ui.screens.settings.SettingsScreen
import app.simt.echo.ui.screens.settings.SettingsViewModel
import app.simt.echo.ui.screens.share.ShareScreen
import app.simt.echo.ui.shell.EchoShell
import app.simt.echo.ui.shell.echoEnterTransition
import app.simt.echo.ui.shell.echoExitTransition
import app.simt.echo.ui.shell.echoPopEnterTransition
import app.simt.echo.ui.shell.echoPopExitTransition
import app.simt.echo.ui.shell.shellConfigFor
import app.simt.echo.ui.sigil.routeSigil
import kotlin.random.Random

/**
 * Navigation shell (plan §8.2). This file is owned by Lane A: screens
 * register destinations here through their owning slices; nobody edits
 * the graph to add screen content.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun EchoNavHost(
    graph: AppGraph,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    // Current destination drives the shell chrome (W8-05). Each destination
    // reports its own route — no back-stack introspection, no versioned
    // NavDestination APIs — so the chrome always matches the visible body.
    var currentRoute by remember { mutableStateOf<EchoRoute>(EchoRoute.Home) }
    val tier = cosmosMotionTier()
    EchoShell(
        config = shellConfigFor(currentRoute),
        onBack = { navController.popBackStack() },
        onClose = { navController.popBackStack() },
        onSettings = { navController.navigate(EchoRoute.Settings) },
        onRecord = { if (currentRoute !is EchoRoute.Record) navController.navigate(EchoRoute.Record()) },
        onReceive = { if (currentRoute !is EchoRoute.Receive) navController.navigate(EchoRoute.Receive) },
        recordEnabled = currentRoute !is EchoRoute.Record,
        receiveEnabled = currentRoute !is EchoRoute.Receive,
        modifier = modifier,
    ) {
    // Shared elements (W13-02): one transition scope over the whole graph,
    // so a route's sigil can fly from its Home record to RouteDetail.
    SharedTransitionLayout {
    CompositionLocalProvider(LocalEchoSharedScope provides this) {
    NavHost(
        navController = navController,
        startDestination = EchoRoute.Home,
        modifier = Modifier,
        // Route-change motif (Cosmos §4.5, Luminous §3 springs): forward
        // enters from the end edge, back returns from the start edge; the
        // tier is read once per composition; STATIC settles with no motion.
        enterTransition = { echoEnterTransition(tier) },
        exitTransition = { echoExitTransition(tier) },
        popEnterTransition = { echoPopEnterTransition(tier) },
        popExitTransition = { echoPopExitTransition(tier) },
    ) {
        echoDestination<EchoRoute.Home> {
            LaunchedEffect(Unit) { currentRoute = EchoRoute.Home }
            val draftIds = graph.draftRepository.listDraftIds()
            HomeScreen(
                draftIds = draftIds,
                draftNames = draftIds.associateWith { draftDisplayName(graph.draftRepository, it) },
                savedRoutes = graph.routeRepository.list(),
                onOpen = navController::navigateTo,
                recoverableDrafts = listRecoverable(graph.draftRepository),
            )
        }
        echoDestination<EchoRoute.Record> { entry ->
            val route = entry.toRoute<EchoRoute.Record>()
            LaunchedEffect(route) { currentRoute = route }
            RecordScreen(
                drafts = graph.draftRepository,
                onSaved = { draftId -> navController.navigateTo(reviewAfterRecord(draftId)) },
                config = graph.configRepository.get(),
                resumeDraftId = route.resumeDraftId,
            )
        }
        echoDestination<EchoRoute.Review> { entry ->
            val route = entry.toRoute<EchoRoute.Review>()
            LaunchedEffect(route) { currentRoute = route }
            val mediaTools = rememberMediaTools()
            val viewModel = remember(route.draftId, mediaTools) {
                ReviewViewModel(
                    draftId = route.draftId,
                    drafts = graph.draftRepository,
                    routes = graph.routeRepository,
                    media = mediaTools,
                    nowMs = ::currentEpochMs,
                    newId = { "lm-${Random.nextLong()}" },
                    api = graph.apiClient,
                    router = graph.aiRouter(),
                    preferLocal = graph.configRepository.get().preferLocalModel,
                )
            }
            ReviewScreen(
                viewModel = viewModel,
                onSaved = {
                    navController.navigateTo(
                        EchoRoute.RouteDetail(it, savedJobId = viewModel.ui.value.savedJobId),
                    )
                },
                onBack = { navController.popBackStack() },
                onFindLandmarks = { navController.navigateTo(EchoRoute.Processing(route.draftId)) },
            )
        }
        echoDestination<EchoRoute.Processing> { entry ->
            val route = entry.toRoute<EchoRoute.Processing>()
            LaunchedEffect(route) { currentRoute = route }
            val mediaTools = rememberMediaTools()
            // Back-stack-scoped, not `remember`: leaving the screen must
            // cancel polling via onCleared() (review 0001 R3).
            val viewModel: ProcessingViewModel = viewModel(
                key = route.draftId,
                factory = viewModelFactory {
                    addInitializer(ProcessingViewModel::class) {
                        ProcessingViewModel(
                            draftId = route.draftId,
                            drafts = graph.draftRepository,
                            media = mediaTools,
                            api = graph.apiClient,
                            nowMs = ::currentEpochMs,
                            aiConfigured = graph.cloudAiConfigured,
                        )
                    }
                },
            )
            ProcessingScreen(
                viewModel = viewModel,
                onDone = { navController.popBackStack() },
            )
        }
        echoDestination<EchoRoute.RouteDetail> { entry ->
            val route = entry.toRoute<EchoRoute.RouteDetail>()
            LaunchedEffect(route) { currentRoute = route }
            RouteDetailScreen(
                routeId = route.routeId,
                routes = graph.routeRepository,
                onShare = { navController.navigateTo(EchoRoute.Share(it)) },
                onNavigate = { navController.navigateTo(alignForRoute(it, simulate = false)) },
                onSimulate = { navController.navigateTo(alignForRoute(it, simulate = true)) },
                onBack = { navController.popBackStack() },
                onDeleted = { navController.popBackStack() },
                api = graph.apiClient,
                savedJobId = route.savedJobId,
                transferService = graph.transferService,
            )
        }
        echoDestination<EchoRoute.Share> { entry ->
            val route = entry.toRoute<EchoRoute.Share>()
            LaunchedEffect(route) { currentRoute = route }
            ShareScreen(
                routeId = route.routeId,
                service = graph.transferService,
                haptics = rememberHaptics(),
                sharing = rememberFileSharing(),
                onClose = { navController.popBackStack() },
                sigil = remember(route.routeId) { graph.routeRepository.get(route.routeId)?.let(::routeSigil) },
            )
        }
        echoDestination<EchoRoute.Receive> {
            LaunchedEffect(Unit) { currentRoute = EchoRoute.Receive }
            ReceiveScreen(
                service = graph.transferService,
                sharing = rememberFileSharing(),
                onImported = { navController.navigateTo(EchoRoute.RouteDetail(it)) },
            )
        }
        echoDestination<EchoRoute.Align> { entry ->
            val route = entry.toRoute<EchoRoute.Align>()
            LaunchedEffect(route) { currentRoute = route }
            AlignScreen(
                routeId = route.routeId,
                routes = graph.routeRepository,
                onAligned = { navController.navigateTo(navigateAfterAlign(route)) },
                onBack = { navController.popBackStack() },
            )
        }
        echoDestination<EchoRoute.Navigate> { entry ->
            val route = entry.toRoute<EchoRoute.Navigate>()
            LaunchedEffect(route) { currentRoute = route }
            // W7-04 Simulate: replay the saved geometry instead of the
            // live source. Falls back to live when the route is gone —
            // the screen's empty state still owns that case.
            val liveMotion = rememberMotionSource()
            val scope = rememberCoroutineScope()
            val motion = remember(route.routeId, route.simulate) {
                if (!route.simulate) {
                    liveMotion
                } else {
                    graph.routeRepository.get(route.routeId)?.let {
                        ReplayMotionSource(captureLogForRoute(it), scope)
                    } ?: liveMotion
                }
            }
            NavigateScreen(
                routeId = route.routeId,
                routes = graph.routeRepository,
                onArrived = { navController.navigateTo(EchoRoute.Arrived(it)) },
                onBack = { navController.popBackStack() },
                config = graph.configRepository.get(),
                trials = graph.trialRepository,
                device = graph.platform.name,
                baselineMode = graph.configRepository.get().baselineMode,
                motionSource = motion,
            )
        }
        echoDestination<EchoRoute.Arrived> { entry ->
            val route = entry.toRoute<EchoRoute.Arrived>()
            LaunchedEffect(route) { currentRoute = route }
            ArrivedScreen(
                routeId = route.routeId,
                routes = graph.routeRepository,
                onDone = { navController.popBackStack(EchoRoute.Home, false) },
                trials = graph.trialRepository,
            )
        }
        echoDestination<EchoRoute.Settings> {
            LaunchedEffect(Unit) { currentRoute = EchoRoute.Settings }
            val sharing = rememberFileSharing()
            // Back-stack-scoped like every other screen ViewModel
            // (review 0001 pattern; review 0002 R2 for this regression).
            val vm: SettingsViewModel = viewModel(
                factory = viewModelFactory {
                    addInitializer(SettingsViewModel::class) {
                        SettingsViewModel(
                            configs = graph.configRepository,
                            trials = graph.trialRepository,
                            sharing = sharing,
                            routes = graph.routeRepository,
                            drafts = graph.draftRepository,
                            onPhoneModelName = graph.onPhoneModelPath
                                ?.substringAfterLast('/'),
                            cloudAiConfigured = graph.cloudAiConfigured,
                        )
                    }
                },
            )
            SettingsScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
            )
        }
    }
    }
    }
    }
}

/** A destination that exposes its enter/exit scope to `echoSharedElement` (W13-02). */
private inline fun <reified T : Any> NavGraphBuilder.echoDestination(
    noinline content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) {
    composable<T> { entry ->
        CompositionLocalProvider(LocalEchoDestinationScope provides this) { content(entry) }
    }
}

private fun NavHostController.navigateTo(route: EchoRoute) = navigate(route)

/**
 * Home draft label (W7-03): the route name the user typed, else the
 * destination, else blank (Home falls back to "Untitled draft" — raw
 * ids never reach screen copy).
 */
private fun draftDisplayName(drafts: DraftRepository, draftId: String): String {
    val capture = runCatching { drafts.loadCapture(draftId) }.getOrNull() ?: return ""
    return when {
        capture.routeName.isNotBlank() -> capture.routeName
        capture.destinationLabel.isNotBlank() -> capture.destinationLabel
        else -> ""
    }
}
