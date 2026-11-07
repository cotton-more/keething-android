plugins {
    alias(libs.plugins.keething.kotlin.library)
    `java-test-fixtures`
}

dependencies {
    api(project(":core:domain"))

    // KeePass's schema in the tests (SchemaCheck), shared with core:vault.
    testFixturesImplementation(libs.kotlin.test.junit5)
}

// Every file a test writes is checked against KDBX_XML.xsd (SchemaCheck, found by JUnit through its service file).
tasks.withType<Test>().configureEach {
    systemProperty("junit.jupiter.extensions.autodetection.enabled", "true")
}

// The large fictitious database of app.large-database's measurements: `task large:database -- <new.kdbx> …`.
tasks.register<JavaExec>("makeLargeDatabase") {
    description = "A large fictitious database (password pw) for the phone's measurements"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.keething.kdbx.probe.MakeLargeDatabaseKt")
    args(providers.gradleProperty("arguments").getOrElse("").split(' ').filter { it.isNotEmpty() })
    maxHeapSize = "4g"
}

// The 40 MiB database the save's memory budget is tested with (core:vault memoryBudgetTest; app.large-database AC2):
// task large:database's defaults but Argon2 of 1 MiB, so that the key derivation's own memory does not count.
val largeDatabaseFixture by tasks.registering(JavaExec::class) {
    description = "The large fictitious database of the memory budget test"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.keething.kdbx.probe.MakeLargeDatabaseKt")
    val output = layout.buildDirectory.file("large/large-5000.kdbx")
    outputs.file(output)
    inputs.files(sourceSets["main"].output, sourceSets["test"].output)
    doFirst { output.get().asFile.delete() }
    argumentProviders += CommandLineArgumentProvider { listOf(output.get().asFile.absolutePath, "5000", "40", "1", "1") }
    maxHeapSize = "4g"
}
