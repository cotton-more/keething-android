package dev.keething.android.settings

import android.app.UiModeManager

/**
 * Settings' "Theme" (keething-spec app.theme-choice; SPEC §6.2): as the system, always light or always dark. Kept in the
 * app's settings and handed to the system as the app's night mode (`UiModeManager.setApplicationNightMode`, which has
 * no getter), again at each start: every window of the app follows it (the splash, a quick note, autofill's screens),
 * and the screens rebuild themselves when it changes.
 */
internal enum class ThemeChoice(val label: String, val stored: String, val nightMode: Int) {
    System("System", "system", UiModeManager.MODE_NIGHT_AUTO),
    Light("Light", "light", UiModeManager.MODE_NIGHT_NO),
    Dark("Dark", "dark", UiModeManager.MODE_NIGHT_YES),
    ;

    companion object {
        /** The stored choice; nothing or anything unknown is the system's. */
        fun of(stored: String?): ThemeChoice = entries.firstOrNull { it.stored == stored } ?: System
    }
}
