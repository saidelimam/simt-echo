package app.simt.echo

import android.app.Application
import app.simt.echo.platform.AndroidPlatform
import app.simt.echo.BuildConfig

/**
 * Process entrypoint. Owns the single [AppGraph]; slices register their
 * Android actuals (crypto provider, stores, transports) on it as they land.
 */
class EchoApplication : Application() {
    val graph: AppGraph by lazy {
        AppGraph(
            platform = AndroidPlatform(dataDir = filesDir.absolutePath),
            apiBaseUrl = BuildConfig.WORKERS_API_BASE,
            appToken = BuildConfig.ECHO_APP_TOKEN,
            // W5-01: a .litertlm pushed into the app's models directory
            // (adb) enables the on-phone provider; absent = LAN/manual.
            onPhoneModelPath = getExternalFilesDir("models")
                ?.listFiles()
                ?.firstOrNull { it.isFile && it.name.endsWith(".litertlm") }
                ?.absolutePath,
        )
    }
}
