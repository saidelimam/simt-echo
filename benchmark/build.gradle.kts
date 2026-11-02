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
