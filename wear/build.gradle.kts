plugins {
    alias(libs.plugins.androidApplication)
}

android {
    // ECHO watch listener (plan W5-04 residual: the phone-side mirror had no
    // receiver). Plain Views only, so the composeCompiler plugin is
    // deliberately absent — it errors without a Compose runtime on the
    // classpath.
    namespace = "app.simt.echo.wear"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        // MUST equal the phone app's applicationId: a divergence makes the
        // Data Layer fail silently — no error, no delivery.
        applicationId = "app.simt.echo"
        // Wear OS 3+ baseline; Wear and phone target SDKs differ by design
        // for Play Console reasons — do not "align" them.
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.play.services.wearable)

    testImplementation(libs.junit.junit)
}
