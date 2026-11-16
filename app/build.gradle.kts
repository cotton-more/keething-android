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
 * nothing compiled changes with the time, and the configuration cache keeps no time of its own. The same source makes
 * the same APK (keething-spec app.fdroid-build): the time is SOURCE_DATE_EPOCH's when set, else the commit's when
 * nothing is uncommitted, else the clock's. F-Droid's scanner deletes the Gradle wrapper's jar before it builds; that
 * alone is not an uncommitted change.
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
        val dirty = git("status", "--porcelain", "--", ".", ":!gradle/wrapper/gradle-wrapper.jar")?.isNotEmpty() == true
        val built = (System.getenv("SOURCE_DATE_EPOCH") ?: git("log", "-1", "--format=%ct").takeUnless { dirty })
            ?.toLongOrNull()?.let(Instant::ofEpochSecond) ?: Instant.now().truncatedTo(ChronoUnit.SECONDS)
        // Written by hand: Properties.store would add a time of its own.
        output.file("build-info.properties").get().asFile.writeText(
            "version=${versionName.get()}\nversionCode=${versionCode.get()}\nbuilt=$built\ncommit=$commit\ndirty=$dirty\n",
        )
    }
}

android {
    namespace = "dev.keething.android"
    compileSdk = 37
    // The NDK core/argon2 is built with, which also strips its library here.
    ndkVersion = libs.versions.ndk.get()

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
            // A measurement build keeps Log.i, the timing lines (keething-spec app.large-database; DEVELOPMENT.md
            // "Measuring a large database"): -Pkeething.timings. tools/build-apk.sh builds a shipped APK without it.
            if (!providers.gradleProperty("keething.timings").map(String::toBoolean).getOrElse(false)) proguardFile("proguard-no-log.pro")
        }
    }

    buildFeatures {
        compose = true
    }

    // No dependency block in the APK's signing block: Google's encrypted list, which F-Droid's scanner flags
    // (keething-spec app.fdroid-build).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
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

/**
 * The repository's LICENSE and SECURITY.md, shown by About (SPEC §6.2 "About"), as the assets `LICENSE.txt` and
 * `SECURITY.md`: the app's text and the repository's stay the same.
 */
abstract class RepositoryTexts : DefaultTask() {
    @get:InputFile
    abstract val license: RegularFileProperty

    @get:InputFile
    abstract val security: RegularFileProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun copy() {
        license.get().asFile.copyTo(output.file("LICENSE.txt").get().asFile, overwrite = true)
        security.get().asFile.copyTo(output.file("SECURITY.md").get().asFile, overwrite = true)
    }
}

androidComponents {
    onVariants { variant ->
        val texts = tasks.register<RepositoryTexts>("${variant.name}RepositoryTexts") {
            license.set(rootProject.layout.projectDirectory.file("LICENSE"))
            security.set(rootProject.layout.projectDirectory.file("SECURITY.md"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(texts, RepositoryTexts::output)
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
    // The native Argon2 (keething-spec unlock.faster-argon2): the reference C, CC0 / Apache 2.0, `assets/licenses`.
    implementation(project(":core:argon2"))
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
    // An SVG image's one-time code QR (keething-spec edit.one-time-code AC8): drawn first, ImageDecoder does not; Apache 2.0.
    implementation(libs.androidsvg)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    // FakeGrantedFolder: New database's files through a granted folder, as the phone's storage provider behaves.
    testImplementation(testFixtures(project(":core:domain")))
}
