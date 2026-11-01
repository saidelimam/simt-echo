package app.simt.echo

import app.simt.echo.platform.Platform
import app.simt.echo.platform.createPlatform

/**
 * Manual dependency graph for the shared module.
 *
 * One graph per process, created by the platform entry point
 * (`EchoApplication` on Android). Slices register their stores, sources and
 * transports here as they land; `shared` deliberately has no DI framework —
 * Hilt, where used, stays inside `androidApp` actuals and never crosses the
 * KMP boundary (see `docs/plans/0001-agentic-flow.md` §4).
 */
class AppGraph(
    val platform: Platform = createPlatform(),
)
