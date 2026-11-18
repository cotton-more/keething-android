package dev.keething.android.settings

import dev.keething.android.R
import dev.keething.android.ui.UiText
import dev.keething.android.ui.uiPlain
import dev.keething.android.ui.uiText
import java.util.Locale

/**
 * Settings' "Language" (keething-spec app.language-setting): the language Android keeps for the app (its per-app
 * language, `LocaleManager.applicationLocales`, chosen on the system's page the row opens). None chosen is the
 * system's; a chosen one is named in itself ("English", "Русский"), as the system's own list names it.
 */
internal object AppLanguage {
    fun label(chosen: Locale?): UiText =
        if (chosen == null || chosen.language.isEmpty()) {
            uiText(R.string.settings_language_system)
        } else {
            uiPlain(chosen.getDisplayLanguage(chosen).replaceFirstChar { it.titlecase(chosen) })
        }
}
