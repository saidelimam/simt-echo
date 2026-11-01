package app.simt.echo.host

import androidx.activity.ComponentActivity
import org.junit.runner.Description
import org.junit.rules.TestWatcher
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController

/**
 * Host-test activity harness (review 0006 NR-1).
 *
 * AGP's `androidHostTest` source set runs no Android resource pipeline:
 * no manifest is merged and no `R` classes are generated. The stock
 * Compose rule launches its activity through `ActivityScenario`, which
 * resolves the activity through the (absent) manifest and fails. This
 * rule instantiates a plain [ComponentActivity] directly with
 * `Robolectric.buildActivity(...).setup()` — no manifest lookup — and
 * the Compose rule under test hosts its content on it instead.
 */
class RobolectricActivityRule : TestWatcher() {

    private var controller: ActivityController<ComponentActivity>? = null

    val activity: ComponentActivity
        get() = checkNotNull(controller).get()

    override fun starting(description: Description) {
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
    }

    override fun finished(description: Description) {
        controller?.pause()?.stop()?.destroy()
        controller = null
    }
}
