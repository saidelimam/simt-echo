package app.simt.echo.host

import android.content.res.Resources
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowResources
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * String fallback for the host UI gate (review 0006 NR-1).
 *
 * The stub `R` classes in this source set give every library id a stable
 * int, but there is no resource table behind them. Real lookups (e.g.
 * Material3's text-field error string) would throw
 * `NotFoundException`. This shadow answers real ids from the real
 * [Resources] and returns a fixed marker for the stub ids. The marker is
 * never asserted on — tests assert the app's own copy, which never comes
 * from a library string.
 */
@Implements(Resources::class)
class HostTestResourcesShadow : ShadowResources() {

    @RealObject
    private lateinit var realResources: Resources

    @Implementation
    protected fun getText(id: Int): CharSequence {
        return try {
            Shadow.directlyOn(
                realResources,
                Resources::class.java,
                "getText",
                ClassParameter.from(Int::class.javaPrimitiveType, id),
            )
        } catch (e: Resources.NotFoundException) {
            HOST_TEST_STRING
        }
    }

    companion object {
        const val HOST_TEST_STRING = "host-test"
    }
}
