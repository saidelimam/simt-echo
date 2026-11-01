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
        // W0-03 wires navigation + lifecycle (nav shell, screen ViewModels).
        // CameraX, Media3, Ktor, ML Kit, QR and crypto stay catalog-only
        // until the slice that first imports them.
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
            // Navigation shell (W0-03) and per-screen ViewModels. These are
            // the org.jetbrains multiplatform artifacts, so iOS keeps
            // compiling; Android-only camera/ML Kit/Ktor stay catalog-only
            // until their slices wire them.
            implementation(libs.androidx.navigation.compose)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            // W1-02 CameraRecorder (CameraX). ImageCapture ships inside
            // camera-core; preview uses camera-compose's CameraXViewfinder.
            implementation(libs.camerax.core)
            implementation(libs.camerax.camera2)
            implementation(libs.camerax.lifecycle)
            implementation(libs.camerax.video)
            implementation(libs.camerax.compose)
            // W5-04 Wear haptic mirror (MessageClient + CapabilityClient).
            implementation(libs.play.services.wearable)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.core)
            // Repository tests (W0-04) run on the host against okio's fake.
            implementation(libs.okio.fakefilesystem)
            // runTest for flow-collecting engine tests (W1-01).
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
