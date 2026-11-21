package dev.keething.android.unlock

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import dev.keething.android.session.AppLocks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** A password typed on a lock screen and not sent goes at the app's next lock (keething-spec security.auto-lock AC8). */
class LockScreenFieldsTest {
    private var now = 0L
    private val locks = AppLocks { now }
    private val fields = LockScreenFields().also { fields -> locks.onLock = fields::locked }

    private fun typed(text: String, revealed: Boolean): TypedPassword = TypedPassword().also {
        it.text.setTextAndPlaceCursorAtEnd(text)
        it.revealed = revealed
        fields.add(it)
    }

    @Test
    fun aCountedLockEmptiesEveryLockScreensTypedPasswordAndTurnsItsEyeOff() {
        val personal = typed("hunter2", revealed = true)
        val work = typed("half typed", revealed = false)

        locks.locked()

        assertEquals("", personal.text.text.toString())
        assertEquals("", work.text.text.toString())
        assertFalse(personal.revealed, "the eye off")
    }

    @Test
    fun comingBackAfterTheTimeoutWithAPasswordTypedFindsTheFieldEmptyWithinItTheTypingStays() {
        val field = typed("hunter2", revealed = true)

        locks.movedToBackground()
        now += 59_000
        locks.movedToForeground(1.minutes)
        assertEquals("hunter2", field.text.text.toString(), "back within the timeout: what was typed stays")
        assertTrue(field.revealed)

        locks.movedToBackground()
        now += 60_000
        locks.movedToForeground(1.minutes)
        assertEquals("", field.text.text.toString())
        assertFalse(field.revealed)
    }

    @Test
    fun aLockScreenGoneIsNotKept() {
        val gone = typed("hunter2", revealed = true)
        fields.remove(gone)
        locks.locked()
        assertEquals("hunter2", gone.text.text.toString(), "no longer followed")
    }
}
