package app.simt.echo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlin.random.Random
import app.simt.echo.AppGraph
import app.simt.echo.platform.currentEpochMs
import app.simt.echo.platform.rememberHaptics
import app.simt.echo.ui.screens.HomeScreen
import app.simt.echo.ui.screens.detail.RouteDetailScreen
import app.simt.echo.ui.screens.PlaceholderScreen
import app.simt.echo.ui.screens.PlaceholderViewModel
import app.simt.echo.ui.screens.receive.ReceiveScreen
import app.simt.echo.ui.screens.record.RecordScreen
import app.simt.echo.ui.screens.review.ReviewScreen
import app.simt.echo.ui.screens.review.ReviewViewModel
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
            )
        }
        composable<EchoRoute.Record> {
            RecordScreen(
                drafts = graph.draftRepository,
                onSaved = { navController.popBackStack() },
            )
        }
        composable<EchoRoute.Review> { entry ->
            val route = entry.toRoute<EchoRoute.Review>()
            val viewModel = remember(route.draftId) {
                ReviewViewModel(
                    draftId = route.draftId,
                    drafts = graph.draftRepository,
                    routes = graph.routeRepository,
                    media = graph.mediaTools,
                    nowMs = ::currentEpochMs,
                    newId = { "lm-${Random.nextLong()}" },
                )
            }
            ReviewScreen(
                viewModel = viewModel,
                onSaved = { navController.navigateTo(EchoRoute.RouteDetail(it)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable<EchoRoute.Processing> { entry ->
            val route = entry.toRoute<EchoRoute.Processing>()
            val vm = remember(route.draftId) {
                PlaceholderViewModel("Processing ${route.draftId}", "Consent + upload lands in W3-02.")
            }
            PlaceholderScreen(vm, "Back home", { navController.popBackStack() })
        }
        composable<EchoRoute.RouteDetail> { entry ->
            val route = entry.toRoute<EchoRoute.RouteDetail>()
            RouteDetailScreen(
                routeId = route.routeId,
                routes = graph.routeRepository,
                onShare = { navController.navigateTo(EchoRoute.Share(it)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable<EchoRoute.Share> { entry ->
            val route = entry.toRoute<EchoRoute.Share>()
            ShareScreen(
                routeId = route.routeId,
                service = graph.transferService,
                haptics = rememberHaptics(),
                onClose = { navController.popBackStack() },
            )
        }
        composable<EchoRoute.Receive> {
            ReceiveScreen(
                service = graph.transferService,
                onImported = { navController.navigateTo(EchoRoute.RouteDetail(it)) },
            )
        }
        composable<EchoRoute.Align> { entry ->
            val route = entry.toRoute<EchoRoute.Align>()
            val vm = remember(route.routeId) {
                PlaceholderViewModel("Align ${route.routeId}", "Start alignment lands in W2-06.")
            }
            PlaceholderScreen(vm, "Start navigating", { navController.navigateTo(EchoRoute.Navigate(route.routeId, false)) })
        }
        composable<EchoRoute.Navigate> { entry ->
            val route = entry.toRoute<EchoRoute.Navigate>()
            val vm = remember(route.routeId, route.simulate) {
                PlaceholderViewModel("Navigate ${route.routeId}", "Navigator lands in W2-05/W2-06.")
            }
            PlaceholderScreen(vm, "Finish", { navController.navigateTo(EchoRoute.Arrived("demo-trial")) })
        }
        composable<EchoRoute.Arrived> { entry ->
            val route = entry.toRoute<EchoRoute.Arrived>()
            val vm = remember(route.trialId) {
                PlaceholderViewModel("Arrived", "Trial ${route.trialId} saved (W4-01 extends this).")
            }
            PlaceholderScreen(vm, "Done", { navController.popBackStack(EchoRoute.Home, false) })
        }
        composable<EchoRoute.Settings> {
            val vm = remember { PlaceholderViewModel("Settings", "Calibration lands in W4-02.") }
            PlaceholderScreen(vm, "Back home", { navController.popBackStack() })
        }
    }
}

private fun NavHostController.navigateTo(route: EchoRoute) = navigate(route)
