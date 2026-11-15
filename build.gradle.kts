plugins {
    // Gives the root project the `check` task that Spotless adds its check to.
    base
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.android.lint) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.spotless)
}

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
    format("misc") {
        target(
            "gradle/*.toml",
            "gradle/wrapper/*.properties",
            "config/**/*.yml",
            "gradle.properties",
            ".gitignore",
            ".gitattributes",
            ".editorconfig",
        )
        trimTrailingWhitespace()
        endWithNewline()
    }
}
