plugins {
    alias(libs.plugins.keething.kotlin.library)
    // FakeGrantedFolder, the storage as the Pixel 6 behaves, shared with core:vault's tests.
    `java-test-fixtures`
}
