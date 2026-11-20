import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.detekt)
    alias(libs.plugins.android.library)
}

// The Media3 integration: Media3 plays through the core (specs/api.md, "Media3").
// Android only, so a plain Android library with AGP's Kotlin.
android {
    namespace = "com.novage.p2pml.media3"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        // Pinned, as :core's: by default it follows compileSdk.
        aarMetadata { minCompileSdk = libs.versions.android.minCompileSdk.get().toInt() }
        // On a device, by hand with connectedAndroidTest; `build` does not run it.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The published bytecode, whatever JDK builds it, as :core's.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    lint {
        warningsAsErrors = true
        // The SDK levels are chosen (gradle/libs.versions.toml), and a new
        // release of a dependency must not fail the build.
        disable += setOf("OldTargetApi", "GradleDependency", "NewerVersionAvailable")
    }
}

kotlin {
    // Public API only where it says so: the library goes to Maven Central.
    explicitApi()

    compilerOptions {
        allWarningsAsErrors = true
        jvmTarget = JvmTarget.JVM_11
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
}

// The tasks with type resolution: the release variant has the same sources.
tasks.named("check") { dependsOn("detektDebug", "detektDebugAndroidTest") }

// Without type resolution, it would analyse the same code a second time.
tasks.named("detekt") { enabled = false }

dependencies {
    api(project(":core"))
    api(libs.media3.exoplayer.hls)

    // With JUnit: outside a KMP module, the Kotlin plugin does not choose it.
    androidTestImplementation(kotlin("test-junit"))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.okhttp.mockwebserver3)
}
