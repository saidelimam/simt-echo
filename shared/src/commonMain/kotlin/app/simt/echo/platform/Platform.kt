package app.simt.echo.platform

/** Runtime platform the shared code is executing on. */
interface Platform {
    val name: String
}

/** Creates the [Platform] for the current target (Android / iOS actuals). */
expect fun createPlatform(): Platform
