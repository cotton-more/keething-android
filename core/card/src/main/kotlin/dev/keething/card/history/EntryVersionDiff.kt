package dev.keething.card.history

import dev.keething.card.CardText
import dev.keething.card.FieldType
import dev.keething.domain.text.UserText
import dev.keething.domain.vault.CustomField
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.FieldValue
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** What changed in one field between two versions of an entry (Mac SPEC §3.22, `VersionChange`). */
public data class VersionChange(
    /** The field's name ("Password", a custom field's own, "Attachment scan.pdf"). */
    val field: UserText,
    val kind: Kind,
    /**
     * The values as written (null: not there), or what changed in words ("protected", "type Card number"); a protected
     * one is shown masked until revealed.
     */
    val old: UserText?,
    val new: UserText?,
    val isProtected: Boolean,
) {
    public enum class Kind { ADDED, REMOVED, CHANGED }
}

/** Every difference between two versions of an entry (Mac `EntryVersionDiff`). */
public object EntryVersionDiff {
    /**
     * From [old] to [new], in the card's order: the standard fields, Notes' protection, the kind of entry (SPEC §6.4),
     * the custom fields (by name, in [new]'s order,
     * then the removed ones; a change of protection or of type; the order), tags, expiry, the one-time code (that
     * it changed, never its values), attachments. [day] writes the expiry's date, [size] an attachment's size.
     */
    public fun changes(
        old: Entry,
        new: Entry,
        day: (Instant) -> String = ::mediumDate,
        size: (Int?) -> UserText = ::sizeText,
    ): List<VersionChange> {
        val result = mutableListOf<VersionChange>()
        fun compare(field: UserText, a: String?, b: String?, protected: Boolean = false) {
            val before = a?.ifEmpty { null }
            val after = b?.ifEmpty { null }
            if (before == after) return
            val kind = when {
                before == null -> VersionChange.Kind.ADDED
                after == null -> VersionChange.Kind.REMOVED
                else -> VersionChange.Kind.CHANGED
            }
            result += VersionChange(field, kind, before?.let(UserText::plain), after?.let(UserText::plain), protected)
        }
        fun compare(field: CardText, a: String?, b: String?, protected: Boolean = false) = compare(UserText.of(field), a, b, protected)
        fun protection(protected: Boolean) = UserText.of(if (protected) CardText.CHANGE_PROTECTED else CardText.CHANGE_NOT_PROTECTED)
        // A recovery phrase is masked as a protected value is, protected or not.
        fun secret(name: String): Boolean =
            listOf(old.fieldTypes[name], new.fieldTypes[name]).any { it?.let(FieldType::of)?.isNeverCopied == true }
        fun typeText(name: String?): UserText =
            if (name == null) UserText.of(CardText.CHANGE_NO_TYPE) else UserText.of(CardText.CHANGE_TYPE, FieldType.of(name)?.fullTitle ?: UserText.plain(name))

        compare(CardText.TITLE, old.title, new.title)
        compare(CardText.ROW_USERNAME, old.username, new.username)
        compare(CardText.ROW_PASSWORD, old.password.reveal(), new.password.reveal(), protected = true)
        compare(CardText.ROW_URL, old.url, new.url)
        // A protected text is masked, as it was protected before or is now (SPEC §6.4).
        compare(CardText.ROW_NOTES, old.notes, new.notes, protected = old.notesProtected || new.notesProtected)
        if (old.notes == new.notes && new.notes.isNotEmpty() && old.notesProtected != new.notesProtected) {
            result += VersionChange(
                UserText.of(CardText.ROW_NOTES), VersionChange.Kind.CHANGED,
                protection(old.notesProtected), protection(new.notesProtected),
                isProtected = false,
            )
        }
        compare(CardText.CHANGE_KIND, old.entryType, new.entryType)

        // The first field of a name, as the Mac keeps it.
        val oldFields = LinkedHashMap<String, CustomField>().apply { old.customFields.forEach { putIfAbsent(it.name, it) } }
        val newNames = new.customFields.map { it.name }.toSet()
        for (field in new.customFields) {
            val before = oldFields[field.name]
            val value = field.text()
            val name = UserText.plain(field.name)
            if (before == null && value.isEmpty()) {
                result += VersionChange(name, VersionChange.Kind.ADDED, null, UserText.plain(""), isProtected = false)
                continue
            }
            compare(name, before?.text(), value, protected = field.isProtected || before?.isProtected == true || secret(field.name))
            if (before != null && before.text() == value && before.isProtected != field.isProtected) {
                result += VersionChange(name, VersionChange.Kind.CHANGED, protection(before.isProtected), protection(field.isProtected), isProtected = false)
            }
            // Its type (Mac SPEC §3.12), when it had the field before.
            val oldType = old.fieldTypes[field.name]
            val newType = new.fieldTypes[field.name]
            if (before != null && oldType != newType) {
                result += VersionChange(name, VersionChange.Kind.CHANGED, typeText(oldType), typeText(newType), isProtected = false)
            }
        }
        for (field in old.customFields) {
            if (field.name in newNames) continue
            compare(UserText.plain(field.name), field.text(), null, protected = field.isProtected || secret(field.name))
            if (field.text().isEmpty()) result += VersionChange(UserText.plain(field.name), VersionChange.Kind.REMOVED, UserText.plain(""), null, isProtected = false)
        }
        if (old.customFields.map { it.name } != new.customFields.map { it.name } && old.customFields.map { it.name }.toSet() == newNames) {
            result += VersionChange(UserText.of(CardText.CHANGE_FIELD_ORDER), VersionChange.Kind.CHANGED, null, null, isProtected = false)
        }

        compare(CardText.CHANGE_TAGS, old.tags.joinToString(", "), new.tags.joinToString(", "))
        // Expires without a time: "yes".
        fun expiry(entry: Entry): UserText? =
            if (entry.expires) entry.expiryTime?.let(day)?.let(UserText::plain) ?: UserText.of(CardText.CHANGE_YES) else null
        val (oldExpiry, newExpiry) = expiry(old) to expiry(new)
        if (oldExpiry != newExpiry) {
            val kind = when {
                oldExpiry == null -> VersionChange.Kind.ADDED
                newExpiry == null -> VersionChange.Kind.REMOVED
                else -> VersionChange.Kind.CHANGED
            }
            result += VersionChange(UserText.of(CardText.CHANGE_EXPIRES), kind, oldExpiry, newExpiry, isProtected = false)
        }
        if (old.totp != new.totp) {
            val kind = when {
                old.totp == null -> VersionChange.Kind.ADDED
                new.totp == null -> VersionChange.Kind.REMOVED
                else -> VersionChange.Kind.CHANGED
            }
            result += VersionChange(UserText.of(CardText.ROW_ONE_TIME_CODE), kind, null, null, isProtected = true)
        }

        val oldFiles = LinkedHashMap<String, Int?>().apply { old.attachments.forEach { if (it.name !in this) put(it.name, it.size) } }
        val newFiles = LinkedHashMap<String, Int?>().apply { new.attachments.forEach { if (it.name !in this) put(it.name, it.size) } }
        fun attachment(name: String) = UserText.of(CardText.CHANGE_ATTACHMENT, name)
        for (file in new.attachments) {
            if (file.name in oldFiles) {
                val before = oldFiles[file.name]
                if (before != file.size) result += VersionChange(attachment(file.name), VersionChange.Kind.CHANGED, size(before), size(file.size), isProtected = false)
            } else {
                result += VersionChange(attachment(file.name), VersionChange.Kind.ADDED, null, size(file.size), isProtected = false)
            }
        }
        for (file in old.attachments) {
            if (file.name !in newFiles) result += VersionChange(attachment(file.name), VersionChange.Kind.REMOVED, size(file.size), null, isProtected = false)
        }
        return result
    }

    private fun CustomField.text(): String = when (val v = value) {
        is FieldValue.Plain -> v.text
        is FieldValue.Protected -> v.secret.reveal()
    }

    /** "1024 bytes" in the app's words; the app gives its own, as the phone writes a size. */
    internal fun sizeText(bytes: Int?): UserText = bytes?.let { UserText.counted(CardText.BYTES, it) } ?: UserText.of(CardText.SIZE_UNKNOWN)

    private fun mediumDate(instant: Instant): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(instant.atZone(ZoneId.systemDefault()))
}
