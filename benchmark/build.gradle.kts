plugins {
    alias(libs.plugins.androidTest)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "app.simt.echo.benchmark"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // W10-04: the profiled app. Benchmarks and the Baseline Profile
    // generator below target its release journeys; see docs/perf.md.
    targetProjectPath = ":androidApp"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // Pins the coroutines version the app itself resolves (1.11.0, via Compose
    // and lifecycle), so the stale 1.3.4/1.7.1 that androidx.test pulls in
    // transitively is never fetched.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation(libs.benchmark.macro.junit4)
    implementation(libs.test.ext.junit)
    implementation(libs.uiautomator)
}

// W10-04: profiles generate on a connected device (benchmark 1.2+ needs no
// root on API 33+). No Gradle-managed device is declared: device runs are
// named manual gates, never pre-commit gates (ADR-017, docs/perf.md).
baselineProfile {
    useConnectedDevices = true
}
