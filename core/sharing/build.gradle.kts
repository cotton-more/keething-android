plugins {
    alias(libs.plugins.keething.kotlin.library)
}

dependencies {
    api(project(":core:domain"))
    // Argon2 for the pictures of a request (SPEC §10).
    implementation(project(":core:kdbx"))
    // The one-time code of a received entry, written as KeePassXC's otp link.
    implementation(project(":core:totp"))
    // What of a wallet never goes (SPEC §10.1): read as the card reads it.
    api(project(":core:card"))
    api(libs.kotlinx.coroutines.core)
}
