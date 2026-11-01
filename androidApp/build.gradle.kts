import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "app.simt.echo"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    // Plan §0.6: app secrets live in the gitignored local.properties and
    // reach the app only as BuildConfig constants — never committed.
    val localProperties = Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use(::load)
    }

    defaultConfig {
        // Plan §0.6: BuildConfig constants from gitignored local.properties.
        buildConfigField("String", "ECHO_APP_TOKEN", "\"" + (localProperties.getProperty("ECHO_APP_TOKEN", "") ?: "") + "\"")
        buildConfigField("String", "WORKERS_API_BASE", "\"" + (localProperties.getProperty("WORKERS_API_BASE", "") ?: "") + "\"")
        applicationId = "app.simt.echo"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
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

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
}
