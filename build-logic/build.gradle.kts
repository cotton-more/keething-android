plugins {
    `kotlin-dsl`
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // compileOnly: the root build puts the Kotlin plugin on the classpath, so every module shares one copy.
    compileOnly(libs.kotlin.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("kotlinLibrary") {
            id = libs.plugins.keepassbar.kotlin.library.get().pluginId
            implementationClass = "KotlinLibraryConventionPlugin"
        }
    }
}
