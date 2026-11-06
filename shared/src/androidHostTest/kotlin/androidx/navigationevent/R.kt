package androidx.navigationevent

/**
 * Test-only stand-in for the R class AGP 9 generates for application modules
 * (`compile_and_runtime_r_class_jar`) but not for library-module local unit
 * tests. The `navigationevent` AAR ships without its R class, and
 * `ComponentActivity` (1.11+) references `R.id.view_tree_navigation_event_dispatcher_owner`
 * during `setContentView` — Robolectric UI tests in this library module fail
 * with `NoClassDefFoundError: androidx/navigationevent/R$id` without it.
 *
 * The id is used as a `ViewTree` map key, so its value only needs to be a
 * stable non-zero resource id.
 */
@Suppress("ClassName", "unused")
class R private constructor() {
    object id {
        const val view_tree_navigation_event_dispatcher_owner = 0x7f0b0001
    }
}
