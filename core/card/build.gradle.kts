plugins {
    alias(libs.plugins.keething.kotlin.library)
}

dependencies {
    api(project(":core:domain"))
    // english(): a core text in the app's English, so the tests keep the words the user sees.
    testImplementation(testFixtures(project(":core:domain")))
    implementation(project(":core:totp"))
    implementation(project(":core:search"))
}
