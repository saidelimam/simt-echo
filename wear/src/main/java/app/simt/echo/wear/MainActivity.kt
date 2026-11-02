package app.simt.echo.wear

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/**
 * Minimal launcher screen: the watch app is a background listener, and this
 * activity only proves it is installed and awaiting the phone's mirror.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this).apply {
            text = getString(R.string.wear_listening)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
        }
        setContentView(status)
    }
}
