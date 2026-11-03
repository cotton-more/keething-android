import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * What About says of this build (SPEC §6.2 "About"): its version, when it was built and from which commit, with
 * uncommitted changes or not. Written at every build, as the asset `build-info.properties`, not as BuildConfig fields:
 * nothing compiled changes with the time, and the configuration cache keeps no time of its own. F-Droid's reproducible
 * build sets SOURCE_DATE_EPOCH: its time is then the build's, so the same source makes the same APK.
 */
abstract class BuildInfo : DefaultTask() {
    @get:Input
    abstract val versionName: Property<String>

    @get:Input
    abstract val versionCode: Property<Int>

    @get:Internal
    abstract val repository: DirectoryProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @get:Inject
    abstract val exec: ExecOperations

    @TaskAction
    fun write() {
        fun git(vararg args: String): String? {
            val out = ByteArrayOutputStream()
            val result = exec.exec {
                commandLine("git", *args)
                workingDir = repository.get().asFile
                standardOutput = out
                errorOutput = ByteArrayOutputStream()
                isIgnoreExitValue = true
            }
            return out.toString().trim().takeIf { result.exitValue == 0 }
        }
        val commit = git("rev-parse", "--short", "HEAD").orEmpty()
        val dirty = git("status", "--porcelain")?.isNotEmpty() == true
        val built = System.getenv("SOURCE_DATE_EPOCH")?.toLongOrNull()?.let(Instant::ofEpochSecond) ?: Instant.now().truncatedTo(ChronoUnit.SECONDS)
        // Written by hand: Properties.store would add a time of its own.
        output.file("build-info.properties").get().asFile.writeText(
            "version=${versionName.get()}\nversionCode=${versionCode.get()}\nbuilt=$built\ncommit=$commit\ndirty=$dirty\n",
        )
    }
}

android {
    namespace = "dev.keething.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.keething.android"
        minSdk = 34
        targetSdk = 37
        versionCode = 1
        versionName = "0.0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
            // The fictitious databases the tests read (SPEC §9).
            val fixtures = rootDir.resolve("fixtures")
            it.inputs.dir(fixtures).withPropertyName("fixtures")
            it.systemProperty("keething.fixtures", fixtures.absolutePath)
        }
    }
}

/** The app's own licence in About (SPEC §6.2 "About"): the repository's LICENSE, as the asset `LICENSE.txt`. */
abstract class LicenseAsset : DefaultTask() {
    @get:InputFile
    abstract val license: RegularFileProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun copy() {
        license.get().asFile.copyTo(output.file("LICENSE.txt").get().asFile, overwrite = true)
    }
}

androidComponents {
    onVariants { variant ->
        val license = tasks.register<LicenseAsset>("${variant.name}LicenseAsset") {
            this.license.set(rootProject.layout.projectDirectory.file("LICENSE"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(license, LicenseAsset::output)
        val info = tasks.register<BuildInfo>("${variant.name}BuildInfo") {
            versionName.set(android.defaultConfig.versionName)
            versionCode.set(android.defaultConfig.versionCode)
            repository.set(rootProject.layout.projectDirectory)
            // The time and the working tree change with every build.
            outputs.upToDateWhen { false }
        }
        variant.sources.assets?.addGeneratedSourceDirectory(info, BuildInfo::output)
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
    }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:vault"))
    implementation(project(":core:search"))
    implementation(project(":core:totp"))
    implementation(project(":core:card"))
    implementation(project(":core:autofill"))
    implementation(project(":core:generator"))
    implementation(project(":core:sharing"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.process)
    implementation(libs.lifecycle.viewmodel.navigation3)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    // The list and the card side by side on a wide screen (SPEC §6.1).
    implementation(libs.compose.material3.adaptive.navigation3)
    implementation(libs.autofill)
    // QR codes of a password and a Wi-Fi network (SPEC §6.2); MIT, `assets/licenses`.
    implementation(libs.qrcodegen)
    // Reading the sharing's QR codes (SPEC §10): the camera, and ZXing to decode a frame or a picture; Apache 2.0.
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.zxing.core)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    // FakeGrantedFolder: New database's files through a granted folder, as the phone's storage provider behaves.
    testImplementation(testFixtures(project(":core:domain")))
}
