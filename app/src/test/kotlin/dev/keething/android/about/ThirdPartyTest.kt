package dev.keething.android.about

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * About's third-party list kept true by the build (keething-spec app.dependency-licences): the release's Maven artifacts
 * (Licensee's report, written before the tests) and the files copied into the source (app/third-party-files.txt).
 */
class ThirdPartyTest {
    private val root = File("..")

    /** Each artifact of the release as `group:artifact`, from Licensee's artifacts.json. */
    private fun releaseArtifacts(): List<String> {
        val report = File(System.getProperty("keething.licensee") ?: error("keething.licensee is not set")).readText()
        val coordinates = Regex(""""groupId":\s*"([^"]+)",\s*"artifactId":\s*"([^"]+)"""")
        return coordinates.findAll(report).map { "${it.groupValues[1]}:${it.groupValues[2]}" }.toList()
    }

    private fun matches(pattern: String, artifact: String): Boolean =
        Regex(pattern.split("*").joinToString(".*") { Regex.escape(it) }).matches(artifact)

    // spec: app.dependency-licences/AC2
    @Test
    fun everyArtifactOfTheReleaseIsAPartAboutShowsAndEveryPartsArtifactsAreThere() {
        val artifacts = releaseArtifacts()
        assertTrue(artifacts.size > 100, "Licensee's report read: ${artifacts.size} artifacts")
        val unshown = artifacts.filter { artifact -> THIRD_PARTY.none { part -> part.artifacts.any { matches(it, artifact) } } }
        assertEquals(emptyList(), unshown, "in the release, not in About's list (THIRD_PARTY)")
        val twice = artifacts.filter { artifact -> THIRD_PARTY.count { part -> part.artifacts.any { matches(it, artifact) } } > 1 }
        assertEquals(emptyList(), twice, "shown by two parts")
        val gone = THIRD_PARTY.flatMap { it.artifacts }.filter { pattern -> artifacts.none { matches(pattern, it) } }
        assertEquals(emptyList(), gone, "in About's list, not in the release")
    }

    private class Copied(val sha256: String, val path: String, val licence: String)

    private fun copied(): List<Copied> = File("third-party-files.txt").readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { line -> line.split('\t').let { Copied(it[0], it[1], it[2]) } }

    /** The files a copy from another project would be among: what the app bundles that is not code of its own. */
    private fun bundled(): Set<String> {
        fun under(folder: String, keep: (File) -> Boolean = { true }): List<String> =
            File(root, folder).walkTopDown().filter { it.isFile && keep(it) }.map { it.relativeTo(root).invariantSeparatorsPath }.toList()
        val resources = File(root, "core").listFiles()!!.flatMap { under("core/${it.name}/src/main/resources") }
        return (
            under("app/src/main/res/font") +
                under("app/src/main/assets") { "licenses" !in it.invariantSeparatorsPath } +
                resources +
                under("core/argon2/src/main/cpp/phc-winner-argon2") { it.name != "KEETHING.md" }
            ).toSet()
    }

    // spec: app.dependency-licences/AC3
    @Test
    fun everyFileCopiedFromAnotherProjectIsNamedWithItsHashAndALicenceAboutShows() {
        val lines = copied()
        assertEquals(bundled(), lines.map { it.path }.toSet(), "app/third-party-files.txt names every copied file, and only those")
        val changed = lines.filter { line ->
            val bytes = File(root, line.path).readBytes()
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } != line.sha256
        }
        assertEquals(emptyList(), changed.map { it.path }, "changed since app/third-party-files.txt: its new hash there, in the same commit")
        val shown = THIRD_PARTY.map { it.file }.toSet()
        assertEquals(emptyList(), lines.map { it.licence }.filter { it !in shown }, "a licence About does not show")
    }
}
