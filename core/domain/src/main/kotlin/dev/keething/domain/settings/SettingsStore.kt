package dev.keething.domain.settings

/** Where small settings are kept: a port, so that the rules around them are tested without Android. */
public interface SettingsStore {
    public fun string(key: String): String?

    /** Null removes the setting. */
    public fun setString(key: String, value: String?)
}
