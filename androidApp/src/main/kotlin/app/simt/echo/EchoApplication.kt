package app.simt.echo

import android.app.Application
import android.os.Build
import app.simt.echo.capture.MlKitFaceScanner
import app.simt.echo.platform.AndroidLiveUpdateSink
import app.simt.echo.platform.AndroidNetworkGate
import app.simt.echo.platform.AndroidPlatform
import app.simt.echo.platform.createLanTransport
import app.simt.echo.BuildConfig

/**
 * Process entrypoint. Owns the single [AppGraph]; slices register their
 * Android actuals (crypto provider, stores, transports) on it as they land.
 */
class EchoApplication : Application() {
    val graph: AppGraph by lazy {
        AppGraph(
            platform = AndroidPlatform(dataDir = filesDir.absolutePath),
            // R12-02: the bearer is minted per install and persisted; no
            // shared app token is compiled into the build.
            apiBaseUrl = BuildConfig.WORKERS_API_BASE,
            // W5-01: a .litertlm pushed into the app's models directory
            // (adb) enables the on-phone provider; absent = LAN/manual.
            onPhoneModelPath = getExternalFilesDir("models")
                ?.listFiles()
                ?.firstOrNull { it.isFile && it.name.endsWith(".litertlm") }
                ?.absolutePath,
            // W13-10: navigation Live Updates draw through this sink.
            liveUpdateSink = AndroidLiveUpdateSink(this, smallIcon = R.drawable.ic_stat_echo),
            sdkInt = Build.VERSION.SDK_INT,
            // W11-04: strip frames are scanned on-device before upload.
            faceScanner = MlKitFaceScanner(),
            // W11-09: walk-time uploads follow the active network's policy.
            networkGate = AndroidNetworkGate(this),
            // R14-01: LAN sharing dials through the Wi-Fi/hotspot network,
            // never the process default (cellular, VPN).
            lanTransport = createLanTransport(this),
        )
    }

    override fun onCreate() {
        super.onCreate()
        // Receiver expiry (plan W3-08): routes whose time has come vanish
        // on start. Missing storage is a no-op, never a crash at launch.
        runCatching {
            graph.purgeExpiredRoutes(System.currentTimeMillis())
        }
    }
}
