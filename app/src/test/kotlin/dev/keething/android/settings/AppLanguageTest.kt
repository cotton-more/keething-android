package dev.keething.android.settings

import dev.keething.android.ui.english
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class AppLanguageTest {
    @Test
    fun noneChosenIsTheSystemsAndAChosenOneIsNamedInItself() {
        assertEquals("System", AppLanguage.label(null).english())
        assertEquals("System", AppLanguage.label(Locale.ROOT).english())
        assertEquals("English", AppLanguage.label(Locale.forLanguageTag("en")).english())
        assertEquals("English", AppLanguage.label(Locale.forLanguageTag("en-GB")).english())
        assertEquals("Русский", AppLanguage.label(Locale.forLanguageTag("ru")).english())
    }
}
