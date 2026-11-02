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
    data class Record(val resumeDraftId: String? = null) : EchoRoute

    @Serializable
    data class Review(val draftId: String) : EchoRoute

    @Serializable
    data class Processing(val draftId: String) : EchoRoute

    @Serializable
    data class RouteDetail(
        val routeId: String,
        /** Server job from the just-completed Review save (W3-08 delete offer). */
        val savedJobId: String? = null,
    ) : EchoRoute

    @Serializable
    data class Share(val routeId: String) : EchoRoute

    @Serializable
    data object Receive : EchoRoute

    @Serializable
    data class Align(val routeId: String, val simulate: Boolean = false) : EchoRoute

    @Serializable
    data class Navigate(val routeId: String, val simulate: Boolean) : EchoRoute

    /**
     * Guided practice tour (W13-13): the bundled fixture walk through the
     * real Navigate instrument. Labelled "Practice", never a saved route —
     * it is never listed among routes and writes neither `routes/` nor
     * `trials/`.
     */
    @Serializable
    data object Practice : EchoRoute

    @Serializable
    data class Arrived(val routeId: String) : EchoRoute

    @Serializable
    data object Settings : EchoRoute
}

/**
 * RouteDetail → Align hop (plan W7-04): carries the debug Simulate choice
 * forward so Align → Navigate can honour it. The mapping is a named
 * function (not an inline literal in the host) so the journey test pins
 * the flag flow against mutation.
 */
fun alignForRoute(routeId: String, simulate: Boolean): EchoRoute.Align =
    EchoRoute.Align(routeId, simulate)

/**
 * Align → Navigate hop (plan W7-04): the flag survives alignment
 * untouched — alignment zeroes the odometer, it never changes the motion
 * source. Navigation inputs/geometry are unaffected (S-reviewed freeze).
 */
fun navigateAfterAlign(align: EchoRoute.Align): EchoRoute.Navigate =
    EchoRoute.Navigate(align.routeId, align.simulate)

/**
 * Record → Review handoff (plan W7-05): the finish sheet's save lands
 * directly on Review for the just-saved draft — never a pop-to-Home.
 * A named function (not an inline literal in the host) so the handoff
 * test pins the draftId flow against mutation.
 */
fun reviewAfterRecord(draftId: String): EchoRoute.Review =
    EchoRoute.Review(draftId)
