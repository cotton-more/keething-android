package dev.keething.android.settings

import dev.keething.domain.settings.SettingsStore

/**
 * The version of what the app keeps in its settings (keething-spec app.upgrade-safety AC2): the database list (which
 * keeps a version of its own too), the settings, Recent, the generator's choices, the fingerprint's switches. An update
 * that changes how one of them is kept adds a step from the version before; [migrate] runs at the start, before
 * anything reads the settings. A version newer than this app knows (a debug build installed over a later one) is left
 * as it is: each reader then takes what it understands and its default for the rest (AC3).
 */
internal object SettingsVersion {
    const val KEY = "storeVersion"

    const val CURRENT = 1

    /** Each step turns the settings of its version into the next one's. */
    private val steps: Map<Int, (SettingsStore) -> Unit> = mapOf(
        // 0: kept before the version was (0.0.1, before 1.0); the same keys and values as 1.
        0 to { _ -> },
    )

    fun migrate(store: SettingsStore) {
        var version = store.string(KEY)?.let { it.toIntOrNull() ?: return } ?: 0
        if (version > CURRENT) return
        while (version < CURRENT) {
            steps.getValue(version)(store)
            version++
            store.setString(KEY, version.toString())
        }
    }
}
