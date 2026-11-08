plugins {
    alias(libs.plugins.android.library)
}

/**
 * The native Argon2 (keething-spec unlock.faster-argon2): the reference C vendored under src/main/cpp, built from
 * source with the NDK and CMake versions pinned in libs.versions.toml (F-Droid builds from source; the same versions make the same
 * library). Its tests run on a phone: `task test:device`.
 */
android {
    namespace = "dev.keething.argon2"
    compileSdk = 37
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // Phones, and the emulator on a Mac or a PC.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }
}

// The fictitious databases the tests open (SPEC §9), as the test APK's assets.
androidComponents {
    onVariants { variant ->
        variant.androidTest?.sources?.assets?.addStaticSourceDirectory(rootDir.resolve("fixtures").path)
    }
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    // NativeArgon2 is an Argon2Engine.
    api(project(":core:kdbx"))

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    // The vectors of the Kotlin Argon2's own tests, the hostile files.
    androidTestImplementation(testFixtures(project(":core:kdbx")))
}

// JUnit 5 comes with core:kdbx's test fixtures, for the JVM tests; the phone's run JUnit 4.
configurations.configureEach {
    if (name.contains("AndroidTest")) {
        exclude(group = "org.junit.jupiter")
        exclude(group = "org.junit.platform")
    }
}
