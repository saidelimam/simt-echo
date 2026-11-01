package app.simt.echo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlin.random.Random
import app.simt.echo.AppGraph
import app.simt.echo.data.listRecoverable
import app.simt.echo.platform.currentEpochMs
import app.simt.echo.platform.rememberFileSharing
import app.simt.echo.platform.rememberMediaTools
import app.simt.echo.platform.rememberHaptics
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

/**
 * Navigation shell (plan §8.2). This file is owned by Lane A: screens
 * register destinations here through their owning slices; nobody edits
 * the graph to add screen content.
 */
@Composable
fun EchoNavHost(
    graph: AppGraph,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = EchoRoute.Home,
        modifier = modifier,
    ) {
        composable<EchoRoute.Home> {
            HomeScreen(
                draftIds = graph.draftRepository.listDraftIds(),
                savedRoutes = graph.routeRepository.list(),
                onOpen = navController::navigateTo,
                recoverableDrafts = listRecoverable(graph.draftRepository),
            )
        }
        composable<EchoRoute.Record> { entry ->
            val route = entry.toRoute<EchoRoute.Record>()
            RecordScreen(
                drafts = graph.draftRepository,
                onSaved = { navController.popBackStack() },
                config = graph.configRepository.get(),
                resumeDraftId = route.resumeDraftId,
            )
        }
        composable<EchoRoute.Review> { entry ->
            val route = entry.toRoute<EchoRoute.Review>()
            val mediaTools = rememberMediaTools()
            val viewModel = remember(route.draftId, mediaTools) {
                ReviewViewModel(
                    draftId = route.draftId,
                    drafts = graph.draftRepository,
                    routes = graph.routeRepository,
                    media = mediaTools,
                    nowMs = ::currentEpochMs,
                    newId = { "lm-${Random.nextLong()}" },
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
        composable<EchoRoute.Processing> { entry ->
            val route = entry.toRoute<EchoRoute.Processing>()
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
                        )
                    }
                },
            )
            ProcessingScreen(
                viewModel = viewModel,
                onDone = { navController.popBackStack() },
            )
        }
        composable<EchoRoute.RouteDetail> { entry ->
            val route = entry.toRoute<EchoRoute.RouteDetail>()
            RouteDetailScreen(
                routeId = route.routeId,
                routes = graph.routeRepository,
                onShare = { navController.navigateTo(EchoRoute.Share(it)) },
                onBack = { navController.popBackStack() },
                onDeleted = { navController.popBackStack() },
                api = graph.apiClient,
                savedJobId = route.savedJobId,
            )
        }
        composable<EchoRoute.Share> { entry ->
            val route = entry.toRoute<EchoRoute.Share>()
            ShareScreen(
                routeId = route.routeId,
                service = graph.transferService,
                haptics = rememberHaptics(),
                sharing = rememberFileSharing(),
                onClose = { navController.popBackStack() },
            )
        }
        composable<EchoRoute.Receive> {
            ReceiveScreen(
                service = graph.transferService,
                sharing = rememberFileSharing(),
                onImported = { navController.navigateTo(EchoRoute.RouteDetail(it)) },
            )
        }
        composable<EchoRoute.Align> { entry ->
            val route = entry.toRoute<EchoRoute.Align>()
            AlignScreen(
                routeId = route.routeId,
                routes = graph.routeRepository,
                onAligned = { navController.navigateTo(EchoRoute.Navigate(route.routeId, false)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable<EchoRoute.Navigate> { entry ->
            val route = entry.toRoute<EchoRoute.Navigate>()
            NavigateScreen(
                routeId = route.routeId,
                routes = graph.routeRepository,
                onArrived = { navController.navigateTo(EchoRoute.Arrived(it)) },
                onBack = { navController.popBackStack() },
                config = graph.configRepository.get(),
                trials = graph.trialRepository,
                device = graph.platform.name,
                baselineMode = graph.configRepository.get().baselineMode,
            )
        }
        composable<EchoRoute.Arrived> { entry ->
            val route = entry.toRoute<EchoRoute.Arrived>()
            ArrivedScreen(
                routeId = route.routeId,
                routes = graph.routeRepository,
                onDone = { navController.popBackStack(EchoRoute.Home, false) },
                trials = graph.trialRepository,
            )
        }
        composable<EchoRoute.Settings> {
            // Back-stack-scoped like every other screen ViewModel
            // (review 0001 pattern; review 0002 R2 for this regression).
            val vm: SettingsViewModel = viewModel(
                factory = viewModelFactory {
                    addInitializer(SettingsViewModel::class) {
                        SettingsViewModel(graph.configRepository)
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

private fun NavHostController.navigateTo(route: EchoRoute) = navigate(route)
