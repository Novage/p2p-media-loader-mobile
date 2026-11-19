import java.util.Properties

plugins {
    // Gives the root project the `check` task that Spotless adds its check to.
    base
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.android.lint) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.spotless)
}

// The NDK's name for this computer, in the path to its tools. native/ uses it too.
val ndkHost =
    System.getProperty("os.name").let {
        when {
            it.startsWith("Mac") -> "darwin-x86_64"
            it.startsWith("Windows") -> "windows-x86_64"
            else -> "linux-x86_64"
        }
    }

extra.set("ndkHost", ndkHost)

// One formatter for every Kotlin and Gradle file: ktfmt leaves no style to
// choose, so code from any author comes out the same.
spotless {
    val ktfmtVersion = libs.versions.ktfmt.get()
    // Each module's source paths, not the whole tree: a walk into build/
    // races with parallel tasks that rewrite it. A new module is covered.
    val modules = subprojects.map { it.projectDir.relativeTo(rootDir).invariantSeparatorsPath }
    kotlin {
        target(modules.map { "$it/src/**/*.kt" })
        ktfmt(ktfmtVersion).kotlinlangStyle()
    }
    kotlinGradle {
        target(listOf("*.gradle.kts") + modules.map { "$it/*.gradle.kts" })
        ktfmt(ktfmtVersion).kotlinlangStyle()
    }
    // The NDK's clang-format, so that its version follows the NDK's.
    format("c") {
        target("native/src/**/*.c", "native/src/**/*.h", "native/test/**/*.c")
        // The style file by path: Spotless passes the code without its path,
        // so clang-format would not find it from the source file.
        clangFormat(libs.versions.clang.format.get())
            .pathToExe(clangFormatPath())
            .style("file:${rootDir.resolve(".clang-format")}")
    }
    format("misc") {
        target(
            "gradle/*.toml",
            "gradle/wrapper/*.properties",
            "config/**/*.yml",
            "gradle.properties",
            ".gitignore",
            ".gitattributes",
            ".editorconfig",
            ".clang-format",
            "native/.clang-tidy",
            "native/CMakeLists.txt",
            "native/cmake/*.cmake",
            "core/*.pro",
        )
        trimTrailingWhitespace()
        endWithNewline()
    }
}

fun clangFormatPath(): String {
    val localProperties = Properties()
    file("local.properties").takeIf { it.exists() }?.reader()?.use(localProperties::load)
    val sdkDir =
        localProperties.getProperty("sdk.dir")
            ?: System.getenv("ANDROID_HOME")
            ?: error("Set sdk.dir in local.properties or ANDROID_HOME")
    return "$sdkDir/ndk/${libs.versions.ndk.get()}/toolchains/llvm/prebuilt/$ndkHost/bin/clang-format"
}
