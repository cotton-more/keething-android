plugins {
    alias(libs.plugins.keething.kotlin.library)
}

dependencies {
    api(project(":core:domain"))
    api(project(":core:search"))
    implementation(project(":core:totp"))
}
