package app.simt.echo.platform

/**
 * Phone → watch haptic mirror (plan W5-04).
 *
 * Enclosed copy model (plan §4): the Data Layer *transport* idea (capability
 * query + directed `MessageClient` send) follows `simt-app`'s `data/wear`
 * (`WearDataLayerTransport`, `DataLayerPaths`), but nothing is imported or
 * linked — this is ECHO-owned code under ECHO names and paths. The only
 * ECHO contribution is the [HapticPattern] payload defined below.
 *
 * Provenance: transport shape copied from
 * `data/wear/.../WearDataLayerTransport.kt` + `DataLayerPaths.kt`
 * (capability + message send); payload codec is new ECHO code.
 */

/** Data Layer identity for the ECHO haptic mirror. Distinct from simt-app paths. */
object EchoWearPaths {
    /** Directed message carrying one [HapticPattern]; phone → watch only. */
    const val MESSAGE_PATH_HAPTICS = "/echo/haptics"

    /** Watch advertises this capability when its ECHO listener is installed. */
    const val CAPABILITY_WEAR_APP = "echo_wear_app"

    /**
     * Change-driven nav mirror (plan W13-12): phone → watch snapshots of
     * arrow, distance, next cue and phase, at most once per second.
     * Haptic cues ride the existing [MESSAGE_PATH_HAPTICS] mirror; this
     * path carries no waveforms.
     */
    const val MESSAGE_PATH_NAV = "/echo/nav"
}

/**
 * Minimal byte codec for the mirror: the pattern name as UTF-8.
 *
 * Names (not ordinals) survive enum reorder; unknown names decode to null so
 * a newer phone never crashes an older watch path and vice versa.
 */
object WearHapticCodec {
    fun encode(pattern: HapticPattern): ByteArray =
        pattern.name.encodeToByteArray()

    fun decode(bytes: ByteArray): HapticPattern? =
        try {
            HapticPattern.valueOf(bytes.decodeToString())
        } catch (_: IllegalArgumentException) {
            null
        }
}

/**
 * Mirrors every [play] to a watch sender without changing local behaviour.
 *
 * Best-effort by contract (W5-04 answers): [mirror] must never throw —
 * the Android sender swallows transport failures — and this decorator
 * additionally guards with `runCatching` so a test double that throws
 * still cannot break phone haptics.
 */
class WearMirroringHaptics(
    private val local: Haptics,
    private val mirror: (HapticPattern) -> Unit,
) : Haptics {
    override fun play(pattern: HapticPattern) {
        local.play(pattern)
        runCatching { mirror(pattern) }
    }
}
