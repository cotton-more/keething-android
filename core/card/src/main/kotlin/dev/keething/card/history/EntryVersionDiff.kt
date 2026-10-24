package dev.keething.card.history

import dev.keething.card.FieldType
import dev.keething.domain.vault.CustomField
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.FieldValue
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** What changed in one field between two versions of an entry (Mac SPEC §3.22, `VersionChange`). */
public data class VersionChange(
    val field: String,
    val kind: Kind,
    /** The values as written (null: not there); a protected one is shown masked until revealed. */
    val old: String?,
    val new: String?,
    val isProtected: Boolean,
) {
    public enum class Kind { ADDED, REMOVED, CHANGED }
}

/** Every difference between two versions of an entry (Mac `EntryVersionDiff`). */
public object EntryVersionDiff {
    /**
     * From [old] to [new], in the card's order: the standard fields, the custom fields (by name, in [new]'s order,
     * then the removed ones; a change of protection or of type; the order), tags, expiry, the one-time code (that
     * it changed, never its values), attachments. [day] writes the expiry's date, [size] an attachment's size.
     */
    public fun changes(
        old: Entry,
        new: Entry,
        day: (Instant) -> String = ::mediumDate,
        size: (Int?) -> String = { it?.let { bytes -> "$bytes bytes" } ?: "size unknown" },
    ): List<VersionChange> {
        val result = mutableListOf<VersionChange>()
        fun compare(field: String, a: String?, b: String?, protected: Boolean = false) {
            val before = a?.ifEmpty { null }
            val after = b?.ifEmpty { null }
            if (before == after) return
            val kind = when {
                before == null -> VersionChange.Kind.ADDED
                after == null -> VersionChange.Kind.REMOVED
                else -> VersionChange.Kind.CHANGED
            }
            result += VersionChange(field, kind, before, after, protected)
        }
        // A recovery phrase is masked as a protected value is, protected or not.
        fun secret(name: String): Boolean =
            listOf(old.fieldTypes[name], new.fieldTypes[name]).any { it?.let(FieldType::of)?.isNeverCopied == true }
        fun typeText(name: String?): String = if (name == null) "no type" else "type " + (FieldType.of(name)?.fullTitle ?: name)

        compare("Title", old.title, new.title)
        compare("Username", old.username, new.username)
        compare("Password", old.password.reveal(), new.password.reveal(), protected = true)
        compare("URL", old.url, new.url)
        compare("Notes", old.notes, new.notes)

        // The first field of a name, as the Mac keeps it.
        val oldFields = LinkedHashMap<String, CustomField>().apply { old.customFields.forEach { putIfAbsent(it.name, it) } }
        val newNames = new.customFields.map { it.name }.toSet()
        for (field in new.customFields) {
            val before = oldFields[field.name]
            val value = field.text()
            if (before == null && value.isEmpty()) {
                result += VersionChange(field.name, VersionChange.Kind.ADDED, null, "", isProtected = false)
                continue
            }
            compare(field.name, before?.text(), value, protected = field.isProtected || before?.isProtected == true || secret(field.name))
            if (before != null && before.text() == value && before.isProtected != field.isProtected) {
                result += VersionChange(
                    field.name, VersionChange.Kind.CHANGED,
                    if (before.isProtected) "protected" else "not protected",
                    if (field.isProtected) "protected" else "not protected",
                    isProtected = false,
                )
            }
            // Its type (Mac SPEC §3.12), when it had the field before.
            val oldType = old.fieldTypes[field.name]
            val newType = new.fieldTypes[field.name]
            if (before != null && oldType != newType) {
                result += VersionChange(field.name, VersionChange.Kind.CHANGED, typeText(oldType), typeText(newType), isProtected = false)
            }
        }
        for (field in old.customFields) {
            if (field.name in newNames) continue
            compare(field.name, field.text(), null, protected = field.isProtected || secret(field.name))
            if (field.text().isEmpty()) result += VersionChange(field.name, VersionChange.Kind.REMOVED, "", null, isProtected = false)
        }
        if (old.customFields.map { it.name } != new.customFields.map { it.name } && old.customFields.map { it.name }.toSet() == newNames) {
            result += VersionChange("Custom field order", VersionChange.Kind.CHANGED, null, null, isProtected = false)
        }

        compare("Tags", old.tags.joinToString(", "), new.tags.joinToString(", "))
        fun expiry(entry: Entry) = if (entry.expires) entry.expiryTime?.let(day) ?: "yes" else null
        compare("Expires", expiry(old), expiry(new))
        if (old.totp != new.totp) {
            val kind = when {
                old.totp == null -> VersionChange.Kind.ADDED
                new.totp == null -> VersionChange.Kind.REMOVED
                else -> VersionChange.Kind.CHANGED
            }
            result += VersionChange("One-time code", kind, null, null, isProtected = true)
        }

        val oldFiles = LinkedHashMap<String, Int?>().apply { old.attachments.forEach { if (it.name !in this) put(it.name, it.size) } }
        val newFiles = LinkedHashMap<String, Int?>().apply { new.attachments.forEach { if (it.name !in this) put(it.name, it.size) } }
        for (file in new.attachments) {
            if (file.name in oldFiles) {
                val before = oldFiles[file.name]
                if (before != file.size) compare("Attachment ${file.name}", size(before), size(file.size))
            } else {
                result += VersionChange("Attachment ${file.name}", VersionChange.Kind.ADDED, null, size(file.size), isProtected = false)
            }
        }
        for (file in old.attachments) {
            if (file.name !in newFiles) result += VersionChange("Attachment ${file.name}", VersionChange.Kind.REMOVED, size(file.size), null, isProtected = false)
        }
        return result
    }

    private fun CustomField.text(): String = when (val v = value) {
        is FieldValue.Plain -> v.text
        is FieldValue.Protected -> v.secret.reveal()
    }

    private fun mediumDate(instant: Instant): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(instant.atZone(ZoneId.systemDefault()))
}
