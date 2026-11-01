package app.simt.echo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.simt.echo.ui.EchoApp

/** Single-activity host for the shared Compose UI. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as EchoApplication).graph
        setContent {
            EchoApp(graph)
        }
    }
}
