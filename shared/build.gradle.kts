import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinxSerialization)
}

kotlin {
    android {
        namespace = "app.simt.echo.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        // AGP 9 KMP libraries do not run commonTest on the host by default;
        // without this there is no JVM test gate for `shared` at all.
        withHostTest {}
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    // No iosX64: Kotlin 2.4 / CMP 1.12 no longer publish an Intel-simulator
    // variant. Device (iosArm64) + Apple Silicon simulator cover development.
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        // Scaffold dependencies only: what the skeleton below compiles against.
        // Slice-specific libraries (CameraX, Media3, Ktor, ML Kit, QR, crypto)
        // stay in the version catalog and are wired by the slice that first
        // uses them (W1-02, W2-02, W2-03, W3-01, ...), so `shared` never
        // bundles a stack no code imports yet.
        commonMain.dependencies {
            // CMP plugin accessors: JetBrains Compose artifacts do not share
            // one version number (material3 versions independently), so the
            // catalog cannot pin them; the plugin resolves the aligned set.
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.okio)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
