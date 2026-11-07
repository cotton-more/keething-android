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

// The save's memory budget (keething-spec app.large-database AC2): a 40 MiB database unlocked and saved in a JVM of its
// own with a small heap, so that a copy of the file brought back fails it. Run by `test` (and so by `task test`).
val memoryBudgetTest by tasks.registering(Test::class) {
    description = "A large database's unlock and saves within a heap of five and a half times its size"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("memory-budget") }
    val database = project(":core:kdbx").tasks.named("largeDatabaseFixture")
    dependsOn(database)
    val file = database.map { it.outputs.files.singleFile }
    inputs.files(database)
    jvmArgumentProviders += CommandLineArgumentProvider { listOf("-Dkeething.largeDatabase=${file.get().absolutePath}") }
    maxHeapSize = "224m"
    // One collector without regions, nearer to how ART holds large arrays than G1's humongous regions.
    jvmArgs("-XX:+UseSerialGC")
}

tasks.named<Test>("test") {
    useJUnitPlatform { excludeTags("memory-budget") }
    dependsOn(memoryBudgetTest)
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

// The wallet's fictitious test database (never-copied checks), written by the phone's core: `task wallet:database -- <new.kdbx>`.
tasks.register<JavaExec>("makeWalletDatabase") {
    description = "A new fictitious database of wallets (password pw) for the phone's checks"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.keething.vault.probe.MakeWalletDatabaseKt")
    args(providers.gradleProperty("output").getOrElse(""))
}

// The card's app addresses' fictitious test database, written by the phone's core: `task app-address:database -- <new.kdbx>`.
tasks.register<JavaExec>("makeAppAddressDatabase") {
    description = "A new fictitious database of app addresses (password pw) for the phone's checks"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.keething.vault.probe.MakeAppAddressDatabaseKt")
    args(providers.gradleProperty("output").getOrElse(""))
}

// A large database's unlock and saves within a limited heap (app.large-database): `task large:save -- <heap> <copy.kdbx> …`.
tasks.register<JavaExec>("saveMemory") {
    description = "Unlock and saves of a copy of a large database (password pw) within the heap given"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.keething.vault.probe.SaveMemoryProbeKt")
    maxHeapSize = providers.gradleProperty("heap").getOrElse("512m")
    // One collector without regions, nearer to how ART holds large arrays than G1's humongous regions.
    jvmArgs("-XX:+UseSerialGC")
    args(providers.gradleProperty("arguments").getOrElse("").split(' ').filter { it.isNotEmpty() })
}
