plugins {
    alias(libs.plugins.keepassbar.kotlin.library)
}

dependencies {
    api(project(":core:domain"))
    implementation(project(":core:kdbx"))
    implementation(project(":core:totp"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
}

// The phone's writing tried on a copy of a real database (ROADMAP step 7): `tools/kdbx-probe.sh <copy.kdbx>`.
tasks.register<JavaExec>("probe") {
    description = "Round trip and lifecycle on a copy of a database; the password on standard input"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.keepassbar.vault.probe.KdbxProbeKt")
    standardInput = System.`in`
    maxHeapSize = "2g"
    args(providers.gradleProperty("database").getOrElse(""))
}
