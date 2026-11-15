plugins {
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
}

dependencies {
    implementation(project(":library"))
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
}
