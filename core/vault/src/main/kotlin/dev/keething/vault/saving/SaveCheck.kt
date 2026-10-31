package dev.keething.vault.saving

import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.EntryChanges
import dev.keething.domain.vault.TotpChange
import dev.keething.domain.vault.Vault
import dev.keething.domain.vault.VaultEdit
import dev.keething.kdbx.editing.AppliedEdit
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The check before a written file replaces the old one (SPEC §3.3 step 5; port of the Mac `KDBXFileSaver.verify`):
 * read back as an unlock would read it, every other entry and every group is exactly as before, and the changed
 * entry exactly as asked.
 */
internal object SaveCheck {
    class Failed(val detail: String) : Exception(detail)

    fun verify(before: Vault, after: Vault, edit: VaultEdit.EntryEdit, applied: AppliedEdit, now: Instant) {
        val old = all(before)
        val new = all(after)
        val id = edit.entryId
        if (old.keys - id != new.keys - id) fail("entries differ")
        for ((other, entry) in old) {
            if (other != id && new[other] != entry) fail("another entry changed")
        }

        // The groups as before, with those a restore made on the way back.
        val groups = before.groups.associateBy { it.id }.toMutableMap()
        for (made in applied.createdGroupIds) {
            val group = after.groups.firstOrNull { it.id == made }
            if (group == null || made in groups) fail("a group is missing")
            groups[made] = group
        }
        if (after.groups.associateBy { it.id } != groups || after.groups.size != groups.size ||
            after.recycleBinEnabled != before.recycleBinEnabled
        ) {
            fail("the groups changed")
        }

        when (edit) {
            is VaultEdit.Update -> {
                val actual = new[id] ?: fail("the edit did not come out as asked")
                val expected = applyTo(old[id] ?: fail("entries differ"), edit.changes).copy(lastModified = actual.lastModified)
                if (actual != expected) fail("the edit did not come out as asked")
            }
            is VaultEdit.Create -> {
                val actual = new[id]
                if (id in old || actual == null) fail("the new entry is missing")
                // Where it went as the file had it: a new level another app made meanwhile is that app's group.
                val group = groups[applied.expectedGroupId ?: edit.group.finalGroupId] ?: fail("the group is missing")
                // A new entry's expiry time is its creation time, as in KeePassXC (it does not expire).
                val expected = applyTo(
                    Entry(
                        id, groupPath = group.path, groupId = group.id, expiryTime = seconds(now), created = seconds(now),
                        lastModified = actual.lastModified,
                        customIconId = applied.customIconId?.takeIf { it in after.customIcons },
                    ),
                    edit.fields,
                )
                if (actual != expected || after.recycleBin.any { it.id == id }) fail("the new entry did not come out as asked")
            }
            is VaultEdit.MoveToRecycleBin, is VaultEdit.Restore -> {
                val actual = new[id]
                val toBin = edit is VaultEdit.MoveToRecycleBin
                if (actual == null || actual.groupId != applied.expectedGroupId || after.recycleBin.any { it.id == id } != toBin) {
                    fail("the entry did not move")
                }
                val expected = (old[id] ?: fail("entries differ"))
                    .copy(groupPath = actual.groupPath, groupId = actual.groupId, lastModified = actual.lastModified)
                if (actual != expected) fail("the moved entry changed")
            }
        }
    }

    /** An entry as [changes] leave it: tags trimmed and times to the second, as the file stores them. */
    private fun applyTo(entry: Entry, changes: EntryChanges): Entry {
        var expected = entry
        changes.title?.let { expected = expected.copy(title = it) }
        changes.username?.let { expected = expected.copy(username = it) }
        changes.password?.let { expected = expected.copy(password = it) }
        changes.url?.let { expected = expected.copy(url = it) }
        changes.notes?.let { expected = expected.copy(notes = it) }
        changes.customFields?.let { fields ->
            expected = expected.copy(customFields = fields.filter { it.name !in EntryChanges.RESERVED_FIELD_NAMES })
        }
        changes.fieldTypes?.let { expected = expected.copy(fieldTypes = it) }
        changes.tags?.let { tags -> expected = expected.copy(tags = tags.map { it.trim() }.filter { it.isNotEmpty() }) }
        changes.expiry?.let { expiry ->
            expected = expected.copy(expires = expiry.expires)
            expiry.time?.let { expected = expected.copy(expiryTime = seconds(it)) }
        }
        changes.totp?.let { expected = expected.copy(totp = (it as? TotpChange.Set)?.config) }
        return expected
    }

    /** Every entry by id: those outside the bin, the settings entry and those in the bin. */
    private fun all(vault: Vault): Map<String, Entry> =
        (vault.entries + listOfNotNull(vault.settings) + vault.recycleBin).associateBy { it.id }

    private fun seconds(time: Instant): Instant = time.truncatedTo(ChronoUnit.SECONDS)

    private fun fail(detail: String): Nothing = throw Failed(detail)
}
