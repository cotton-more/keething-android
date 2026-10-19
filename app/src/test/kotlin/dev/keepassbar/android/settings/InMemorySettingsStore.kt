package dev.keepassbar.android.settings

import dev.keepassbar.domain.settings.SettingsStore

internal class InMemorySettingsStore : SettingsStore {
    private val values = mutableMapOf<String, String>()

    override fun string(key: String): String? = values[key]

    override fun setString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}
