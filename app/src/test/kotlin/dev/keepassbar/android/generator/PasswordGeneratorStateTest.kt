package dev.keepassbar.android.generator

import dev.keepassbar.android.settings.InMemorySettingsStore
import dev.keepassbar.generator.CharacterClass
import dev.keepassbar.generator.GeneratorOptions
import dev.keepassbar.generator.PasswordKind
import dev.keepassbar.generator.Strength
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The Mac `PasswordGeneratorModel` expectations: options remembered, each change a new password, never the password stored. */
class PasswordGeneratorStateTest {
    @Test
    fun optionsAreRememberedEachChangeMakesANewPasswordAndThePasswordIsNeverStored() {
        val store = InMemorySettingsStore()
        val state = PasswordGeneratorState(store)
        assertEquals(GeneratorOptions(), state.options)
        val first = state.password
        assertEquals(20, first.length)

        state.change(state.options.copy(kind = PasswordKind.PIN))
        assertTrue(state.password.length == 6 && state.password != first)
        assertEquals(Strength.WEAK, state.strength)
        val pin = state.password
        state.regenerate()
        assertNotEquals(pin, state.password, "a new one (a 1 in 10⁶ chance of the same)")
        state.change(state.options.copy(pinLength = 99))
        assertEquals(16, state.options.pinLength, "clamped")

        state.change(state.options.copy(kind = PasswordKind.STRONG, classes = setOf(CharacterClass.DIGITS, CharacterClass.LOWER)))
        state.setClass(CharacterClass.DIGITS, on = false)
        state.setClass(CharacterClass.LOWER, on = false)
        assertEquals(setOf(CharacterClass.LOWER), state.options.classes, "the last class stays")

        val again = PasswordGeneratorState(store)
        assertEquals(PasswordKind.STRONG, again.options.kind)
        assertEquals(16, again.options.pinLength)
        assertEquals(setOf(CharacterClass.LOWER), again.options.classes)
        assertFalse(store.string(PasswordGeneratorState.OPTIONS_KEY)!!.contains(state.password), "never the password")
    }
}
