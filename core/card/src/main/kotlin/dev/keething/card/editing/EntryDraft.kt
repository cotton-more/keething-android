package dev.keething.card.editing

import dev.keething.card.FieldDate
import dev.keething.card.FieldType
import dev.keething.domain.secret.Secret
import dev.keething.domain.totp.Base32
import dev.keething.domain.totp.TotpConfig
import dev.keething.domain.vault.EntryChanges
import dev.keething.search.FuzzyNames
import dev.keething.search.SearchNormalizer
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
 * The entry's own one-time code as the editor holds it (keething-spec edit.one-time-code AC6): its secret in Base32, a
 * plain string as the text fields hold theirs, and the settings a site may ask for.
 */
public data class CodeDraft(
    val secret: String,
    val digits: Int = 6,
    val period: Int = 30,
    val algorithm: TotpConfig.Algorithm = TotpConfig.Algorithm.SHA1,
) {
    /** The code to show or save, with a key of its own: wipe it once used. */
    public fun config(): TotpConfig = TotpConfig(Secret.adopt(Base32.decode(secret) ?: ByteArray(0)), digits, period, algorithm)

    // The secret is the key: never in a log.
    override fun toString(): String = "CodeDraft(•••, $digits, $period, $algorithm)"

    public companion object {
        public fun of(config: TotpConfig): CodeDraft = CodeDraft(config.secret.useBytes(Base32::encode), config.digits, config.period, config.algorithm)
    }
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
    /** Notes protected in the file: a note's Protect text (SPEC §6.4); an entry's as loaded, not offered. */
    val notesProtected: Boolean = false,
    /** A note's Website row brought in by Add website (an entry's is always there). */
    val websiteAdded: Boolean = false,
    /** The tags as chips (Mac SPEC §3.24), each once in any case or accent. */
    val tags: List<String> = emptyList(),
    /** A tag being typed; what is still typed when saving is saved too ([savedTags]). */
    val tagInput: String = "",
    val fields: List<FieldDraft> = emptyList(),
    val expires: Boolean = false,
    /** When the entry expires, while [expires]: the entry's own time, else a year from when the editor opened. */
    val expiryTime: Instant = Instant.EPOCH,
    /** The group the entry goes into: an existing entry's own until another is chosen (a move with the save). */
    val groupId: String = "",
    /** New groups made in [groupId] with the save, each inside the one before (keething-spec groups.new-groups AC4). */
    val newGroups: List<String> = emptyList(),
    /** The entry's own one-time code; null: none. Further codes are custom fields holding their links. */
    val code: CodeDraft? = null,
) {
    /** Where the entry goes, as the group picker says it. */
    val group: GroupChoice get() = GroupChoice(groupId, newGroups)

    /** Another group chosen in the picker. */
    public fun choosing(group: GroupChoice): EntryDraft = copy(groupId = group.groupId, newGroups = group.newNames)

    /**
     * A field at the end, of [type]: named after the type at once ("CVC", "CVC 2"; the phone names every type, so
     * the value can be typed first), or without a type and a name, for the user to name (several may wait
     * unnamed). A recovery phrase and its passphrase start protected.
     */
    public fun addField(type: FieldType? = null): EntryDraft =
        copy(fields = fields + FieldDraft(nextFieldId(), type?.let { freeFieldName(it.defaultName) } ?: "", "", isProtected = type?.isNeverCopied == true, typeName = type?.typeName))

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

    // Tags (Mac SPEC §3.24)

    /** What is typed in the tag field: a comma or semicolon makes everything before the last one chips. */
    public fun typingTag(text: String): EntryDraft {
        val last = text.indexOfLast(::isTagSeparator)
        if (last < 0) return copy(tagInput = text)
        val rest = text.substring(last + 1)
        return addingTags(text.substring(0, last)).copy(tagInput = rest.ifBlank { "" })
    }

    /** Enter in the tag field, or a completion tapped: [chosen], else what is typed, becomes a chip. */
    public fun commitTag(chosen: String? = null): EntryDraft = addingTags(chosen ?: tagInput).copy(tagInput = "")

    public fun removeTag(tag: String): EntryDraft = copy(tags = tags - tag)

    /** Backspace in the empty tag field takes the last chip off. */
    public fun removeLastTag(): EntryDraft = if (tagInput.isEmpty() && tags.isNotEmpty()) copy(tags = tags.dropLast(1)) else this

    /** The tags saved: the chips, then what is still typed. */
    public val savedTags: List<String> get() = addingTags(tagInput).tags

    /**
     * The tags of [known] (the vault's, most used first) that match what is typed (those starting with it first,
     * then fuzzy matches, "trvl" → Travel), not on the entry yet; none when nothing is typed.
     */
    public fun tagSuggestions(known: List<String>): List<String> =
        FuzzyNames.matching(tagInput, known.filter { tag -> tags.none { sameTag(it, tag) } }).take(MAXIMUM_TAG_SUGGESTIONS)

    /** Each tag of [text] (commas and semicolons separate several) that the entry does not have yet, in any case. */
    private fun addingTags(text: String): EntryDraft {
        val added = tags.toMutableList()
        for (part in text.split(',', ';')) {
            val tag = part.trim()
            if (tag.isNotEmpty() && added.none { sameTag(it, tag) }) added += tag
        }
        return if (added.size == tags.size) this else copy(tags = added)
    }

    internal fun nextFieldId(): Int = (fields.maxOfOrNull { it.id } ?: 0) + 1

    private companion object {
        const val MAXIMUM_TAG_SUGGESTIONS = 8

        fun isTagSeparator(c: Char) = c == ',' || c == ';'

        fun sameTag(a: String, b: String) = SearchNormalizer.normalize(a) == SearchNormalizer.normalize(b)
    }
}
