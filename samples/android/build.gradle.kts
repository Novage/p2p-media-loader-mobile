plugins {
    alias(libs.plugins.detekt)
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.novage.p2pml.sample"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        // Explicit, so that the app's identity does not follow a package rename.
        applicationId = "com.novage.p2pml.sample"
        minSdk = libs.versions.android.minSdk.get().toInt()
        // Explicit, so that a new compileSdk does not change runtime behaviour.
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    lint {
        warningsAsErrors = true
        // The SDK levels are chosen (gradle/libs.versions.toml), a new release
        // of a dependency must not fail the build, and a sample needs no icon.
        disable +=
            setOf(
                "OldTargetApi",
                "GradleDependency",
                "NewerVersionAvailable",
                "MissingApplicationIcon",
            )
    }
}

kotlin {
    compilerOptions {
        allWarningsAsErrors = true
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
}

// One variant with type resolution: the release variant has the same sources.
tasks.named("check") { dependsOn("detektDebug", "detektDebugUnitTest") }

// Without type resolution, it would analyse the same code a second time.
tasks.named("detekt") { enabled = false }

dependencies {
    implementation(project(":library"))
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
}
