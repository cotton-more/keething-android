package dev.keething.android.unlock

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * A lock screen's typed master password and its eye. Kept in the screen's model, not its saved state: a saved state
 * would put it into a Bundle.
 */
internal class TypedPassword {
    val text = TextFieldState()

    /** The eye: the password shown as typed. */
    var revealed: Boolean by mutableStateOf(false)

    /** Nothing typed, the eye off. */
    fun clear() {
        text.clearText()
        revealed = false
    }
}

/**
 * Every lock screen's typed password, followed while its screen's model lives: a password typed and not sent does not
 * outlive the app's lock (the Lock button, the timeout, the screen off), which empties each and turns its eye off, as it
 * takes New database's form (keething-spec security.auto-lock AC8). Call it on the main thread.
 */
internal class LockScreenFields {
    private val fields = mutableSetOf<TypedPassword>()

    fun add(field: TypedPassword) {
        fields += field
    }

    fun remove(field: TypedPassword) {
        fields -= field
    }

    /** The app locked ([dev.keething.android.session.AppLocks.onLock]). */
    fun locked() {
        fields.forEach(TypedPassword::clear)
    }
}
