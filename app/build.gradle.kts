plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.keepassbar.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.keepassbar.android"
        minSdk = 34
        targetSdk = 37
        versionCode = 1
        versionName = "0.0.1"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:vault"))
    implementation(project(":core:search"))
    implementation(project(":core:totp"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.kotlin.test.junit5)
}
