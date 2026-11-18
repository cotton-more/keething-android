package dev.keething.android.list

import dev.keething.android.R
import dev.keething.android.ui.UiText
import dev.keething.android.ui.uiText

/** The `+` next to the search field (SPEC §6.2, §6.4; the agreed mock-up's menu of three). */
internal enum class NewChoice(val title: UiText, val subtitle: UiText? = null) {
    ENTRY(uiText(R.string.list_new_entry)),
    NOTE(uiText(R.string.list_new_note)),
    NOTE_FROM_CLIPBOARD(uiText(R.string.list_new_note_from_clipboard), uiText(R.string.list_new_note_from_clipboard_detail)),
}
