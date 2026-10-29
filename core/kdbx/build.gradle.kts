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
