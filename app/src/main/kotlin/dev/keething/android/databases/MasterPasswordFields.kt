package dev.keething.android.databases

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.keething.android.R
import dev.keething.android.ui.UiText
import dev.keething.android.ui.uiPlain
import dev.keething.android.ui.uiPlural
import dev.keething.android.ui.uiText
import dev.keething.generator.PasswordGenerator
import dev.keething.generator.PasswordStrength
import dev.keething.generator.RandomSource
import dev.keething.generator.Strength

/**
 * A new master password typed twice (keething-spec databases.new-database AC3, AC4; databases.change-master-key AC2):
 * both fields with one eye, whether they match, the strength line, and a suggested passphrase shown once. New database
 * and Change master key… each keep one. [context]: words the password should not be made of (the database's name).
 * Call it on the main thread.
 */
internal class MasterPasswordFields(private val random: RandomSource, private val context: () -> List<String> = { emptyList() }) {
    enum class Match { MATCHES, DIFFERS }

    /** The strength line: 0–4 segments lit, the words, and the verdict. */
    data class StrengthLine(val level: Int, val text: UiText, val verdict: Strength)

    val password = TextFieldState()
    val again = TextFieldState()

    /** The eye: both fields in clear. */
    var reveals: Boolean by mutableStateOf(false)

    /** A passphrase offered, shown once in clear: Another, Use this. */
    var suggestion: String? by mutableStateOf(null)
        private set

    var passphraseWords: Int by mutableStateOf(4)
        private set

    /** A passphrase put in both fields, and its words (its bits are the generator's, not a guess from its letters). */
    private var used: String? by mutableStateOf(null)
    private var usedWords = 0

    /** Null while the second field is empty. */
    val match: Match?
        get() {
            val again = again.text
            if (again.isEmpty()) return null
            return if (again.toString() == password.text.toString()) Match.MATCHES else Match.DIFFERS
        }

    /** Null while nothing is typed. A weak one is allowed, and said (the Mac's D9). */
    val strength: StrengthLine?
        get() {
            val typed = password.text.toString()
            if (typed.isEmpty()) return null
            if (used != null && used == typed) {
                val bits = PasswordGenerator.masterPassphraseEntropy(usedWords)
                return StrengthLine(if (bits >= 80) 4 else 3, uiPlural(R.plurals.databases_strength_passphrase, usedWords, usedWords, bits.toInt()), Strength.STRONG)
            }
            val estimate = PasswordStrength.estimate(typed, context())
            return when (estimate.verdict) {
                Strength.WEAK -> StrengthLine(
                    1,
                    uiText(R.string.databases_strength_weak, estimate.reasons.firstOrNull()?.let(::uiPlain) ?: uiText(R.string.databases_strength_weak_reason)),
                    Strength.WEAK,
                )
                Strength.FAIR -> StrengthLine(2, uiText(R.string.databases_strength_fair, estimate.reasons.firstOrNull()?.let(::uiPlain) ?: uiText(R.string.databases_strength_fair_reason)), Strength.FAIR)
                Strength.STRONG -> StrengthLine(if (estimate.bits >= 80) 4 else 3, uiText(R.string.databases_strength_strong), Strength.STRONG)
            }
        }

    /** Why the two fields cannot be taken yet; null when they can. */
    val blocker: UiText?
        get() = when {
            password.text.isEmpty() -> uiText(R.string.databases_password_blocker_empty)
            match != Match.MATCHES -> uiText(if (again.text.isEmpty()) R.string.databases_password_blocker_again else R.string.databases_password_blocker_differ)
            else -> null
        }

    /** Suggest a passphrase, and Another: [passphraseWords] EFF words, one raised, a digit and a symbol, shown once. */
    fun suggest() {
        suggestion = PasswordGenerator.masterPassphrase(passphraseWords, random)
    }

    /** Fewer or more words (3–10): another suggestion of that many. */
    fun changeWords(count: Int) {
        val clamped = count.coerceIn(PasswordGenerator.MASTER_PASSPHRASE_WORDS)
        if (clamped == passphraseWords) return
        passphraseWords = clamped
        if (suggestion != null) suggest()
    }

    /** Use this: the passphrase in both fields, the eye open; it goes from the screen, the fields show it. */
    fun useSuggestion() {
        val chosen = suggestion ?: return
        password.setTextAndPlaceCursorAtEnd(chosen)
        again.setTextAndPlaceCursorAtEnd(chosen)
        used = chosen
        usedWords = passphraseWords
        reveals = true
        suggestion = null
    }

    fun closeSuggestion() {
        suggestion = null
    }

    /** What was typed and offered goes. */
    fun clear() {
        suggestion = null
        password.clearText()
        again.clearText()
        used = null
    }
}
