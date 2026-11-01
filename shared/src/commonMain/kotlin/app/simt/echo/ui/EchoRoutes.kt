package app.simt.echo.ui

import kotlinx.serialization.Serializable

/**
 * Type-safe navigation routes (plan §8.2 screen map). Every screen in the
 * MVP loop is reachable from Home; owning slices replace each placeholder
 * without touching this registry.
 */
@Serializable
sealed interface EchoRoute {

    @Serializable
    data object Home : EchoRoute

    @Serializable
    data object Record : EchoRoute

    @Serializable
    data class Review(val draftId: String) : EchoRoute

    @Serializable
    data class Processing(val draftId: String) : EchoRoute

    @Serializable
    data class RouteDetail(val routeId: String) : EchoRoute

    @Serializable
    data class Share(val routeId: String) : EchoRoute

    @Serializable
    data object Receive : EchoRoute

    @Serializable
    data class Align(val routeId: String) : EchoRoute

    @Serializable
    data class Navigate(val routeId: String, val simulate: Boolean) : EchoRoute

    @Serializable
    data class Arrived(val routeId: String) : EchoRoute

    @Serializable
    data object Settings : EchoRoute
}
