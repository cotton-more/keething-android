package dev.keething.android.databases

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EditOff
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import dev.keething.android.R
import dev.keething.android.ui.UiText
import dev.keething.android.ui.resolve
import dev.keething.android.ui.uiText
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.session.SaveRoom
import dev.keething.vault.session.SessionState

/**
 * Why an open database is read-only (keething-spec databases.read-only-mark AC2): too large for this phone to save
 * (app.large-database AC3) or KDBX 3.1 viewed only (kdbx.kdbx3 AC6). One icon for both; the reason is said in words.
 */
internal enum class ReadOnlyReason(@StringRes private val text: Int) {
    TOO_LARGE(R.string.databases_read_only_too_large),
    KDBX3(R.string.databases_read_only_kdbx3),
    ;

    val say: UiText get() = uiText(text)

    companion object {
        /** What is read aloud for the icon. */
        val WORD: UiText = uiText(R.string.databases_read_only)

        /** Null while it can be changed. Too large is said first: updating a KDBX 3.1 one would not be saved either. */
        fun of(state: SessionState?): ReadOnlyReason? {
            val unlocked = state as? SessionState.Unlocked ?: return null
            return when {
                unlocked.room is SaveRoom.TooLarge -> TOO_LARGE
                unlocked.viewOnly -> KDBX3
                else -> null
            }
        }
    }
}

/**
 * An open database that is read-only, on the line under the list's header after those not open (keething-spec
 * databases.read-only-mark AC4): [besideOthers], too large only with the other open databases beside it.
 */
internal data class ReadOnlyDatabase(val line: DatabaseLine, val reason: ReadOnlyReason, val besideOthers: Boolean) {
    val id: DatabaseId get() = line.id

    /** Why, whole: its sheet and what is read aloud. */
    val say: UiText get() = when {
        reason == ReadOnlyReason.KDBX3 -> uiText(R.string.databases_read_only_kdbx3_say)
        besideOthers -> uiText(R.string.databases_read_only_too_large_beside_say)
        else -> uiText(R.string.databases_read_only_too_large_say)
    }

    companion object {
        fun of(line: DatabaseLine, state: SessionState?): ReadOnlyDatabase? {
            val reason = ReadOnlyReason.of(state) ?: return null
            val room = (state as SessionState.Unlocked).room
            return ReadOnlyDatabase(line, reason, besideOthers = room == SaveRoom.TooLarge(alone = false))
        }
    }
}

/** An open database that is read-only, its mark in place of its dot (databases.read-only-mark AC1): a pencil crossed out in its [color]. */
@Composable
internal fun ReadOnlyMark(color: Color, size: Dp) {
    Icon(Icons.Rounded.EditOff, contentDescription = ReadOnlyReason.WORD.resolve(), Modifier.size(size), tint = color)
}
