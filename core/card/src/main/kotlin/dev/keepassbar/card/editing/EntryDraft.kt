package dev.keepassbar.card.editing

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A custom field as typed in the editor; [id] tells the rows apart while names are edited. */
public data class FieldDraft(val id: Int, val name: String, val value: String, val isProtected: Boolean)

/**
 * The editor's fields as typed (SPEC §6.2; Mac `EntryEditorModel`). Plain strings, as the text fields hold them;
 * [EntryEditor] says what is wrong with a draft and what saving it changes.
 */
public data class EntryDraft(
    val title: String = "",
    val username: String = "",
    val password: String = "",
    val url: String = "",
    val notes: String = "",
    /** The tags as typed, separated by commas (or semicolons). */
    val tags: String = "",
    val fields: List<FieldDraft> = emptyList(),
    val expires: Boolean = false,
    /** When the entry expires, while [expires]: the entry's own time, else a year from when the editor opened. */
    val expiryTime: Instant = Instant.EPOCH,
    /** A new entry's group; an existing entry stays where it is (moving it is left to the Mac). */
    val groupId: String = "",
) {
    /** A field without a name at the end, for the user to name (several may wait unnamed). */
    public fun addField(): EntryDraft = copy(fields = fields + FieldDraft(nextFieldId(), "", "", isProtected = false))

    public fun changeField(id: Int, change: (FieldDraft) -> FieldDraft): EntryDraft =
        copy(fields = fields.map { if (it.id == id) change(it) else it })

    public fun removeField(id: Int): EntryDraft = copy(fields = fields.filter { it.id != id })

    /** Expires on [day] (in [zone]), at the time of day it had. */
    public fun expiringOn(day: LocalDate, zone: ZoneId): EntryDraft {
        val time = expiryTime.atZone(zone).toLocalTime()
        return copy(expires = true, expiryTime = day.atTime(time).atZone(zone).toInstant())
    }

    internal fun nextFieldId(): Int = (fields.maxOfOrNull { it.id } ?: 0) + 1
}
