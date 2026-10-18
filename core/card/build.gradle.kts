plugins {
    alias(libs.plugins.keepassbar.kotlin.library)
}

dependencies {
    api(project(":core:domain"))
    implementation(project(":core:totp"))
    implementation(project(":core:search"))
}
