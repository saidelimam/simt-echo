package app.simt.echo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import app.simt.echo.platform.currentEpochMs
import app.simt.echo.ui.EchoApp
import app.simt.echo.ui.atmosphere.ProvideCosmosMotionAmbient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Single-activity host for the shared Compose UI. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as EchoApplication).graph
        // W10-04: debug-only JankStats summaries (logcat `EchoJank`).
        // DebugJankStats exists only in the debug source set; reflection
        // keeps release compiling without it, and the dependency is
        // debugImplementation, so release never loads it.
        if (BuildConfig.DEBUG) {
            runCatching {
                Class.forName("app.simt.echo.DebugJankStats")
                    .getMethod("install", android.app.Activity::class.java)
                    .invoke(null, this)
            }
        }
        setContent {
            // Motion tier from system reduce-motion + battery saver (W8-03
            // resolver, wired in W13-01): the echo field, ripples and every
            // tiered motif lower themselves from here.
            ProvideCosmosMotionAmbient {
                EchoApp(graph)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Expiry purge on resume (audit 0007 R3, closed in W14-04): a route
        // that expired while the app sat in the background vanishes before
        // its row can render. Missing storage is a no-op, never a crash.
        // Off the main thread: the purge parses every saved route.
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                (application as EchoApplication).graph.purgeExpiredRoutes(currentEpochMs())
            }
        }
    }
}
