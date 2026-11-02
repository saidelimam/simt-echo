package app.simt.echo.ui

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Nav args survive process death through saved-state serialization
 * (matters to W4-03 recovery): every parametrized route round-trips.
 */
class RouteSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun reviewRoundTrips() {
        val route: EchoRoute = EchoRoute.Review("d1")
        assertEquals(route, json.decodeFromString<EchoRoute>(json.encodeToString(route)))
    }

    @Test
    fun navigateRoundTripsWithSimulateFlag() {
        val route: EchoRoute = EchoRoute.Navigate("r1", simulate = true)
        assertEquals(route, json.decodeFromString<EchoRoute>(json.encodeToString(route)))
    }

    @Test
    fun alignRoundTrips() {
        val route: EchoRoute = EchoRoute.Align("r1")
        assertEquals(route, json.decodeFromString<EchoRoute>(json.encodeToString(route)))
    }

    @Test
    fun alignRoundTripsWithSimulateFlag() {
        val route: EchoRoute = EchoRoute.Align("r1", simulate = true)
        assertEquals(route, json.decodeFromString<EchoRoute>(json.encodeToString(route)))
    }
}
