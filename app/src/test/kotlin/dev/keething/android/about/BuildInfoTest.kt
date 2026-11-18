package dev.keething.android.about

import dev.keething.android.ui.english
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What About reads of the build-info asset Gradle writes (app/build.gradle.kts, `BuildInfo`). */
class BuildInfoTest {
    // spec: app.about/AC1
    @Test
    fun theVersionTheTimeAndTheCommitAreSaid() {
        val info = BuildInfo.parse("version=0.0.1\nversionCode=1\nbuilt=2026-11-02T18:56:00Z\ncommit=becc85f\ndirty=false\n")

        assertEquals("0.0.1 · build 1", info.versionText.english())
        assertEquals("2 November 2026, 19:56", info.builtText(ZoneId.of("Europe/Berlin")).english())
        assertEquals("becc85f", info.commitText.english())
    }

    // spec: app.about/AC2
    @Test
    fun aBuildWithUncommittedChangesSaysSo() {
        val info = BuildInfo.parse("version=0.0.1\nversionCode=1\nbuilt=2026-11-02T18:56:00Z\ncommit=becc85f\ndirty=true\n")

        assertEquals("becc85f with uncommitted changes", info.commitText.english())
    }

    @Test
    fun whatIsMissingOrUnreadableIsUnknown() {
        val nothing = BuildInfo.parse("")
        assertEquals("unknown · build ?", nothing.versionText.english())
        assertEquals("unknown", nothing.builtText(ZoneId.of("UTC")).english())
        assertEquals("unknown", nothing.commitText.english())

        val odd = BuildInfo.parse("built=yesterday\ngarbage\n=x\ncommit=\n")
        assertNull(odd.built)
        assertEquals("unknown", odd.commitText.english())
        assertEquals(Instant.parse("2026-11-02T18:56:00Z"), BuildInfo.parse("built=2026-11-02T18:56:00Z").built)
    }
}
