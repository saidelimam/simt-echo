package app.simt.echo.ui.atmosphere

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

// Android ambient signals feeding the motion-tier resolver (W8-03). Both
// readers are best-effort one-shot snapshots: animator scale 0 means the
// system (or the user via reduce-motion tooling) disabled animations;
// PowerManager reports battery saver. Either can only lower the tier.

/** True when the system animator duration scale is 0 (animations off). */
@Composable
fun rememberSystemReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
    }
}

/** True when Android battery saver is active. */
@Composable
fun rememberPowerSaveMode(): Boolean {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        runCatching {
            val manager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            manager.isPowerSaveMode
        }.getOrDefault(false)
    }
}

/**
 * Provides the resolved ambient tier for [content]. Explicit overrides win
 * (tests, previews); otherwise system reduce-motion + battery saver feed
 * the pure resolver with [visible] (callers pass false for off-screen or
 * backgrounded surfaces — visibility only ever lowers the tier).
 */
@Composable
fun ProvideCosmosMotionAmbient(
    reduceMotion: Boolean? = null,
    powerSave: Boolean? = null,
    visible: Boolean = true,
    content: @Composable () -> Unit,
) {
    val tier: CosmosMotionTier = resolveCosmosMotionTier(
        reduceMotion = reduceMotion ?: rememberSystemReduceMotion(),
        powerSave = powerSave ?: rememberPowerSaveMode(),
        visible = visible,
    )
    ProvideCosmosMotionTier(tier = tier, content = content)
}
