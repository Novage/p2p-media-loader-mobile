import javax.inject.Inject

plugins { alias(libs.plugins.android.library) }

// The native code: QuickJS and the engine in one shared library, libp2pml.so.
// A module of its own, because the KMP Android library plugin has no native
// build; :library depends on it.
android {
    namespace = "com.novage.p2pml.nativelib"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        // Pinned, as :library's: by default it follows compileSdk.
        aarMetadata { minCompileSdk = libs.versions.android.minCompileSdk.get().toInt() }
        // x86 runs only on old emulators.
        ndk { abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        // Ada (third_party) is C++: libc++ is linked into libp2pml.so, which
        // exports none of it.
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_static" } }
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }

    lint {
        warningsAsErrors = true
        // The SDK levels are chosen (gradle/libs.versions.toml), and a new
        // release of a dependency must not fail the build.
        disable += setOf("GradleDependency", "NewerVersionAvailable")
    }
}

/**
 * Builds the native code for this computer with the SDK's CMake and the NDK's clang-tidy: the JNI
 * library for the JVM tests of :library, and the C tests (see CMakeLists.txt).
 */
abstract class HostNativeBuild : DefaultTask() {
    @get:Inject abstract val exec: ExecOperations

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal abstract val sourceDir: DirectoryProperty

    @get:Internal abstract val cmakeBin: DirectoryProperty

    // Its path names the CMake version, so a new version builds again.
    @get:Input
    val cmakePath: String
        get() = cmakeBin.get().asFile.path

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val jniHeader: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val clangTidy: RegularFileProperty

    // Not an output: ctest writes its logs here, which would make this task out of date after each
    // run of the C tests.
    @get:LocalState abstract val buildDir: DirectoryProperty

    @get:OutputFile abstract val jniLibrary: RegularFileProperty

    @get:OutputFile abstract val testProgram: RegularFileProperty

    @TaskAction
    fun build() {
        val cmake = cmakeBin.file("cmake").get().asFile.path
        val out = buildDir.get().asFile.path
        exec.exec {
            commandLine(
                cmake,
                "-S",
                sourceDir.get().asFile.path,
                "-B",
                out,
                "-G",
                "Ninja",
                "-DCMAKE_MAKE_PROGRAM=${cmakeBin.file("ninja").get().asFile.path}",
                // Asserts on: QuickJS checks for leaked objects when it frees a runtime.
                "-DCMAKE_BUILD_TYPE=Debug",
                "-DP2PML_HOST=ON",
                "-DP2PML_JNI_H=${jniHeader.get().asFile.path}",
                "-DP2PML_CLANG_TIDY=${clangTidy.get().asFile.path}",
            )
        }
        exec.exec { commandLine(cmake, "--build", out) }
    }
}

/** Runs the C tests of the host build. */
abstract class HostNativeTest : DefaultTask() {
    @get:Inject abstract val exec: ExecOperations

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val testProgram: RegularFileProperty

    @get:Internal abstract val cmakeBin: DirectoryProperty

    @get:OutputFile abstract val passed: RegularFileProperty

    @TaskAction
    fun test() {
        exec.exec {
            commandLine(
                cmakeBin.file("ctest").get().asFile.path,
                "--test-dir",
                testProgram.get().asFile.parentFile.path,
                "--output-on-failure",
            )
        }
        passed.get().asFile.writeText("passed\n")
    }
}

val sdk = androidComponents.sdkComponents
val sdkCmakeBin = sdk.sdkDirectory.map { it.dir("cmake/${libs.versions.cmake.get()}/bin") }
// The NDK's tools for this computer.
val ndkHost = rootProject.extra["ndkHost"] as String
val ndkToolchain = sdk.ndkDirectory.map { it.dir("toolchains/llvm/prebuilt/$ndkHost") }
val hostBuildDir = layout.buildDirectory.dir("host")

val hostNativeBuild =
    tasks.register<HostNativeBuild>("hostNativeBuild") {
        sources.from("CMakeLists.txt", ".clang-tidy", "src", "test", "third_party")
        sourceDir = layout.projectDirectory
        cmakeBin = sdkCmakeBin
        jniHeader = ndkToolchain.map { it.file("sysroot/usr/include/jni.h") }
        clangTidy = ndkToolchain.map { it.file("bin/clang-tidy") }
        buildDir = hostBuildDir
        jniLibrary = hostBuildDir.map { it.file(System.mapLibraryName("p2pml")) }
        testProgram = hostBuildDir.map { it.file("engine_test") }
    }

val hostNativeTest =
    tasks.register<HostNativeTest>("hostNativeTest") {
        testProgram = hostNativeBuild.flatMap { it.testProgram }
        cmakeBin = sdkCmakeBin
        passed = layout.buildDirectory.file("host-test/passed")
    }

tasks.named("check") { dependsOn(hostNativeTest) }
