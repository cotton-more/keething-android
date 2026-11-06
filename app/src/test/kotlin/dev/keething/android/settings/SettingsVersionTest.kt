package dev.keething.android.settings

import kotlin.test.Test
import kotlin.test.assertEquals

/** The settings' version and its steps (keething-spec app.upgrade-safety AC2). */
class SettingsVersionTest {
    // spec: app.upgrade-safety/AC2
    @Test
    fun settingsWithoutAVersionAreTheFirstOnesAndGetTheCurrentOneLeavingTheRestAsItWas() {
        val store = InMemorySettingsStore()
        store.setString("lockTimeoutMinutes", "15")
        store.setString("recentEntries", "A:1\nB:2")

        SettingsVersion.migrate(store)

        assertEquals(
            mapOf("lockTimeoutMinutes" to "15", "recentEntries" to "A:1\nB:2", SettingsVersion.KEY to SettingsVersion.CURRENT.toString()),
            store.snapshot(),
        )
    }

    @Test
    fun aNewerOrUnreadableVersionIsLeftAsItIs() {
        for (version in listOf((SettingsVersion.CURRENT + 1).toString(), "two")) {
            val store = InMemorySettingsStore()
            store.setString(SettingsVersion.KEY, version)
            store.setString("lockTimeoutMinutes", "15")

            SettingsVersion.migrate(store)

            assertEquals(mapOf(SettingsVersion.KEY to version, "lockTimeoutMinutes" to "15"), store.snapshot(), version)
        }
    }
}
