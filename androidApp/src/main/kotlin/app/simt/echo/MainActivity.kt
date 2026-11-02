package app.simt.echo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.simt.echo.ui.EchoApp
import app.simt.echo.ui.atmosphere.ProvideCosmosMotionAmbient

/** Single-activity host for the shared Compose UI. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as EchoApplication).graph
        setContent {
            // Motion tier from system reduce-motion + battery saver (W8-03
            // resolver, wired in W13-01): the echo field, ripples and every
            // tiered motif lower themselves from here.
            ProvideCosmosMotionAmbient {
                EchoApp(graph)
            }
        }
    }
}
