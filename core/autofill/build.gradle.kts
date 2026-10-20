plugins {
    alias(libs.plugins.keepassbar.kotlin.library)
}

dependencies {
    api(project(":core:domain"))
    api(project(":core:search"))
    implementation(project(":core:totp"))
}
