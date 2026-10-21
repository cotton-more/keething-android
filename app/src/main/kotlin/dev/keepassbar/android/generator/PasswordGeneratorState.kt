package dev.keepassbar.android.generator

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.keepassbar.domain.settings.SettingsStore
import dev.keepassbar.generator.CharacterClass
import dev.keepassbar.generator.GeneratorOptions
import dev.keepassbar.generator.PasswordGenerator
import dev.keepassbar.generator.RandomSource
import dev.keepassbar.generator.Strength
import dev.keepassbar.generator.SystemRandom

/**
 * The password generator's state (Mac `PasswordGeneratorModel`): the options are a preference and are
 * remembered; the password is not (each opening makes a new one, and it goes with the sheet).
 */
internal class PasswordGeneratorState(private val store: SettingsStore, private val random: RandomSource = SystemRandom) {
    var options: GeneratorOptions by mutableStateOf(GeneratorOptions.decode(store.string(OPTIONS_KEY)))
        private set

    var password: String by mutableStateOf(PasswordGenerator.generate(options, random))
        private set

    /** Bits of the recipe: how many equally likely passwords these options make. */
    val bits: Double get() = PasswordGenerator.entropy(options)

    val strength: Strength get() = Strength.of(bits)

    /** Every change makes a new password and is remembered. */
    fun change(options: GeneratorOptions) {
        val clamped = options.clamped()
        if (clamped == this.options) return
        this.options = clamped
        store.setString(OPTIONS_KEY, clamped.encode())
        regenerate()
    }

    fun regenerate() {
        password = PasswordGenerator.generate(options, random)
    }

    /** The last class cannot be switched off: there would be nothing to draw from. */
    fun setClass(cls: CharacterClass, on: Boolean) {
        val classes = if (on) options.classes + cls else options.classes - cls
        if (classes.isNotEmpty()) change(options.copy(classes = classes))
    }

    companion object {
        const val OPTIONS_KEY = "passwordGenerator"
    }
}
