import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.kotlin
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/** A pure Kotlin/JVM core module: JDK 21, explicit API, JUnit 5 through kotlin.test. */
class KotlinLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")

        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(21)
            explicitApi()
        }

        dependencies {
            "testImplementation"(kotlin("test"))
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }
}
