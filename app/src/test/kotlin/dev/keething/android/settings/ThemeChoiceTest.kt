package dev.keething.android.settings

import dev.keething.android.ui.english
import android.app.UiModeManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ThemeChoiceTest {
    private val store = InMemorySettingsStore()

    @Test
    fun systemLightAndDarkAreTheAppsNightModes() {
        assertEquals(listOf("System", "Light", "Dark"), ThemeChoice.entries.map { it.label.english() })
        assertEquals(UiModeManager.MODE_NIGHT_AUTO, ThemeChoice.System.nightMode)
        assertEquals(UiModeManager.MODE_NIGHT_NO, ThemeChoice.Light.nightMode)
        assertEquals(UiModeManager.MODE_NIGHT_YES, ThemeChoice.Dark.nightMode)
    }

    @Test
    fun theChoiceIsKeptAndTheSystemsIsTheDefault() {
        assertEquals(ThemeChoice.System, AppSettings(store).theme.value)

        AppSettings(store).chooseTheme(ThemeChoice.Dark)
        assertEquals(ThemeChoice.Dark, AppSettings(store).theme.value)

        AppSettings(store).chooseTheme(ThemeChoice.System)
        assertNull(store.string("theme"))
        store.setString("theme", "sepia")
        assertEquals(ThemeChoice.System, AppSettings(store).theme.value)
    }
}
