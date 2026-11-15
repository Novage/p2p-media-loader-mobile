import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.detekt)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    // The KMP Android library plugin registers no lint task of its own.
    alias(libs.plugins.android.lint)
}

kotlin {
    // Public API only where it says so: the library goes to Maven Central and
    // into an XCFramework.
    explicitApi()

    compilerOptions {
        allWarningsAsErrors = true
    }

    android {
        namespace = "com.novage.p2pml"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTest {}
        // What an app that uses the library must compile against. Pinned:
        // by default it follows compileSdk.
        aarMetadata {
            minCompileSdk = 36
        }
        // The published bytecode, whatever JDK builds it.
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        // Here, not in a top-level `lint {}` block: that one configures only
        // the standalone JVM lint task, not the Android one.
        lint {
            warningsAsErrors = true
            // The SDK levels are chosen (gradle/libs.versions.toml), and a new
            // release of a dependency must not fail the build.
            disable += setOf("OldTargetApi", "GradleDependency", "NewerVersionAvailable")
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
}

// The tasks with type resolution; the Android compilations include commonMain.
tasks.named("check") { dependsOn("detektMainAndroid", "detektHostTestAndroid") }

// Without type resolution, it would analyse the same code a second time.
tasks.named("detekt") { enabled = false }
