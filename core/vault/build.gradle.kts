plugins {
    alias(libs.plugins.keething.kotlin.library)
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
    mainClass.set("dev.keething.vault.probe.KdbxProbeKt")
    standardInput = System.`in`
    maxHeapSize = "2g"
    args(providers.gradleProperty("database").getOrElse(""))
}

// The field types' key under the apps' former name renamed, into a new file: `tools/rename-field-types.sh <db> <new>`.
tasks.register<JavaExec>("renameFieldTypes") {
    description = "KeePassBar.FieldTypes renamed to Keething.FieldTypes into a new file; the password on standard input"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.keething.vault.probe.RenameFieldTypesKt")
    standardInput = System.`in`
    maxHeapSize = "2g"
    args(providers.gradleProperty("database").getOrElse(""), providers.gradleProperty("output").getOrElse(""))
}
