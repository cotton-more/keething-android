package dev.keething.android.list

/** The `+` next to the search field (SPEC §6.2, §6.4; the agreed mock-up's menu of three). */
internal enum class NewChoice(val title: String, val subtitle: String? = null) {
    ENTRY("New entry"),
    NOTE("New note"),
    NOTE_FROM_CLIPBOARD("New note from clipboard", "Opens it with the copied text"),
}
