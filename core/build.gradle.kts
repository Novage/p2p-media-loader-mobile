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
        namespace = "com.novage.p2pml.core"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTest {}
        // On a device: the native library on a real ABI. Run by hand with
        // connectedAndroidDeviceTest; `build` does not run it.
        withDeviceTest { instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
        // The classes and methods that native code finds by name.
        optimization {
            consumerKeepRules.publish = true
            consumerKeepRules.files.add(file("consumer-rules.pro"))
        }
        // What an app that uses the library must compile against. Pinned:
        // by default it follows compileSdk.
        aarMetadata {
            minCompileSdk = libs.versions.android.minCompileSdk.get().toInt()
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
        commonMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            implementation(project(":native"))
            implementation(libs.ktor.client.okhttp)
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        getByName("androidHostTest").dependencies {
            implementation(libs.okhttp.mockwebserver3)
            implementation(libs.org.json)
        }
        getByName("androidDeviceTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.androidx.test.runner)
            implementation(libs.okhttp.mockwebserver3)
        }
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
}

// The tasks with type resolution; the Android compilations include commonMain.
tasks.named("check") {
    dependsOn("detektMainAndroid", "detektHostTestAndroid", "detektDeviceTestAndroid")
}

// The JVM host tests load the native library from :native's host build.
val hostNativeDir = project(":native").layout.buildDirectory.dir("host").get()

tasks.withType<Test>().configureEach {
    dependsOn(":native:hostNativeBuild")
    inputs
        .file(hostNativeDir.file(System.mapLibraryName("p2pml")))
        .withPathSensitivity(PathSensitivity.NONE)
    systemProperty("java.library.path", hostNativeDir.asFile.path)
    // JDK 24 and later warn on System.loadLibrary without it.
    // -Xcheck:jni: the JVM checks the JNI calls, as CheckJNI does on Android.
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Xcheck:jni")
}

// Without type resolution, it would analyse the same code a second time.
tasks.named("detekt") { enabled = false }
