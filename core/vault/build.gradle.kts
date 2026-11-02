plugins {
    alias(libs.plugins.keething.kotlin.library)
}

dependencies {
    api(project(":core:domain"))
    implementation(project(":core:kdbx"))
    implementation(project(":core:totp"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
    // FakeGrantedFolder: a granted folder as the phone's storage provider behaves.
    testImplementation(testFixtures(project(":core:domain")))
    // Every file a test writes is checked against KeePass's KDBX_XML.xsd (core:kdbx SchemaCheck).
    testImplementation(testFixtures(project(":core:kdbx")))
}

tasks.withType<Test>().configureEach {
    systemProperty("junit.jupiter.extensions.autodetection.enabled", "true")
}

// The phone's writing tried on a copy of a real database, KeePass's schema included: `tools/kdbx-probe.sh <copy.kdbx>`.
tasks.register<JavaExec>("probe") {
    description = "Round trip and lifecycle on a copy of a database; the password on standard input"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.keething.vault.probe.KdbxProbeKt")
    standardInput = System.`in`
    // KeePass's KDBX_XML.xsd, which every file the probe writes is checked against.
    systemProperty("keething.fixtures", rootDir.resolve("fixtures").absolutePath)
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

// The notes' fictitious test database, written by the phone's core: `task notes:database -- <new.kdbx>`.
tasks.register<JavaExec>("makeNotesDatabase") {
    description = "A new fictitious database of notes (password pw) for the phone's checks"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.keething.vault.probe.MakeNotesDatabaseKt")
    args(providers.gradleProperty("output").getOrElse(""))
}
