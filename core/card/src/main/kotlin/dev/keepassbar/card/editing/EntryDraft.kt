package dev.keepassbar.card.editing

import dev.keepassbar.card.FieldDate
import dev.keepassbar.card.FieldType
import dev.keepassbar.domain.vault.EntryChanges
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * A custom field as typed in the editor; [id] tells the rows apart while names are edited. [typeName]: its type as
 * stored (Mac SPEC §3.12), null for none (it is read by its value); a type this version does not know is kept as
 * it is.
 */
public data class FieldDraft(val id: Int, val name: String, val value: String, val isProtected: Boolean, val typeName: String? = null) {
    /** Its type; null when it has none (or one this version does not know). */
    val type: FieldType? get() = typeName?.let(FieldType::of)

    /** The day a date field holds, for its calendar; null when it holds none. */
    val date: LocalDate?
        get() = FieldDate.parse(value, typed = true)?.date?.let { LocalDate.of(it.year, it.month, it.day ?: 1) }
}

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
    /**
     * A field at the end, of [type]: named after the type at once ("CVC", "CVC 2"; the phone names every type, so
     * the value can be typed first), or without a type and a name, for the user to name (several may wait
     * unnamed).
     */
    public fun addField(type: FieldType? = null): EntryDraft =
        copy(fields = fields + FieldDraft(nextFieldId(), type?.let { freeFieldName(it.defaultName) } ?: "", "", isProtected = false, typeName = type?.typeName))

    /** Gives the field [type]; null: none (read by its value). A field without a name gets the type's. */
    public fun withType(id: Int, type: FieldType?): EntryDraft = changeField(id) { field ->
        val name = if (field.name.isBlank() && type != null) freeFieldName(type.defaultName) else field.name
        field.copy(name = name, typeName = type?.typeName)
    }

    /** A day chosen in a date field's calendar: written in ISO 8601, `2027-10-31` (a card's expiry as its month, `2027-10`). */
    public fun withDate(id: Int, day: LocalDate): EntryDraft = changeField(id) { field ->
        val month = "%04d-%02d".format(Locale.ROOT, day.year, day.monthValue)
        field.copy(value = if (field.type == FieldType.CARD_EXPIRY) month else "%s-%02d".format(Locale.ROOT, month, day.dayOfMonth))
    }

    /** [wanted], or with a number after it when a field (or KeePass itself: "Password") has that name. */
    private fun freeFieldName(wanted: String): String {
        val taken = fields.map { it.name.trim() }.toSet() + EntryChanges.RESERVED_FIELD_NAMES
        var name = wanted
        var n = 2
        while (name in taken) name = "$wanted ${n++}"
        return name
    }

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
