plugins {
    alias(libs.plugins.keepassbar.kotlin.library)
}

dependencies {
    api(project(":core:domain"))
    // Argon2 for the pictures of a request (SPEC §10).
    implementation(project(":core:kdbx"))
    // The one-time code of a received entry, written as KeePassXC's otp link.
    implementation(project(":core:totp"))
    api(libs.kotlinx.coroutines.core)
}
