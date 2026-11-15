import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    // The KMP Android library plugin registers no lint task of its own.
    alias(libs.plugins.android.lint)
}

kotlin {
    // Public API only where it says so: the library goes to Maven Central and
    // into an XCFramework.
    explicitApi()

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
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
