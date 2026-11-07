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
