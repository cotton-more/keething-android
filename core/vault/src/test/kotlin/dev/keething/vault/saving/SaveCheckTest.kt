package dev.keething.vault.saving

import dev.keething.domain.secret.Secret
import dev.keething.domain.vault.CustomField
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.EntryChanges
import dev.keething.domain.vault.Vault
import dev.keething.domain.vault.VaultEdit
import dev.keething.domain.vault.VaultGroup
import dev.keething.kdbx.editing.AppliedEdit
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The check of a written file against the file before it (the Mac `KDBXFileSaver.verify`). */
class SaveCheckTest {
    private val now = Instant.parse("2026-10-20T12:00:00.750Z")
    private val seconds = Instant.parse("2026-10-20T12:00:00Z")
    private val root = VaultGroup("R", "Root", emptyList())
    private val work = VaultGroup("W", "Work", listOf("Work"))
    private val bin = VaultGroup("BIN", "Recycle Bin", listOf("Recycle Bin"))
    private val a = Entry("a", title = "A", password = Secret.of("pa"), groupPath = listOf("Work"), groupId = "W", lastModified = seconds.minusSeconds(60))
    private val b = Entry("b", title = "B", groupPath = listOf("Work"), groupId = "W")
    private val before = Vault(entries = listOf(a, b), groups = listOf(root, work))

    private fun check(after: Vault, edit: VaultEdit, applied: AppliedEdit = AppliedEdit(null)) =
        SaveCheck.verify(before, after, edit, applied, now)

    private fun fails(detail: String, after: Vault, edit: VaultEdit, applied: AppliedEdit = AppliedEdit(null)) {
        val failure = assertFailsWith<SaveCheck.Failed> { check(after, edit, applied) }
        assertEquals(detail, failure.detail)
    }

    @Test
    fun anUpdatePassesWhenItCameOutAsAskedAndNothingElseChanged() {
        val changes = EntryChanges(
            title = "A2", password = Secret.of("new"), customFields = listOf(CustomField.protected("PIN", "1")),
            tags = listOf(" x ", ""), expiry = EntryChanges.Expiry(true, now),
        )
        val edited = a.copy(
            title = "A2", password = Secret.of("new"), customFields = listOf(CustomField.protected("PIN", "1")),
            tags = listOf("x"), expires = true, expiryTime = seconds, lastModified = seconds,
        )

        check(before.copy(entries = listOf(edited, b)), VaultEdit.Update("a", a.lastModified, changes))
    }

    @Test
    fun fieldTypesMustComeOutAsAsked() {
        val edit = VaultEdit.Update("a", a.lastModified, EntryChanges(fieldTypes = mapOf("PIN" to "password")))

        check(before.copy(entries = listOf(a.copy(fieldTypes = mapOf("PIN" to "password"), lastModified = seconds), b)), edit)
        fails("the edit did not come out as asked", before.copy(entries = listOf(a.copy(lastModified = seconds), b)), edit)
        fails("another entry changed", before.copy(entries = listOf(a.copy(fieldTypes = mapOf("PIN" to "password")), b.copy(fieldTypes = mapOf("x" to "url")))), edit)
    }

    @Test
    fun anUpdateThatCameOutOtherwiseIsRefused() {
        val edit = VaultEdit.Update("a", a.lastModified, EntryChanges(title = "A2"))

        fails("the edit did not come out as asked", before.copy(entries = listOf(a.copy(title = "A3"), b)), edit)
        fails("the edit did not come out as asked", before.copy(entries = listOf(a.copy(title = "A2", password = Secret.of("x")), b)), edit)
        fails("another entry changed", before.copy(entries = listOf(a.copy(title = "A2"), b.copy(notes = "?"))), edit)
        fails("entries differ", before.copy(entries = listOf(a.copy(title = "A2"))), edit)
        fails("the groups changed", before.copy(entries = listOf(a.copy(title = "A2"), b), groups = listOf(root)), edit)
        fails("the groups changed", before.copy(entries = listOf(a.copy(title = "A2"), b), recycleBinEnabled = false), edit)
    }

    @Test
    fun aNewEntryIsInItsGroupWithItsFieldsAndKeePassXcsTimes() {
        val fields = EntryChanges(title = "New", username = "me", password = Secret.of("pw1"))
        val created = Entry(
            "n", title = "New", username = "me", password = Secret.of("pw1"), groupPath = listOf("Work"), groupId = "W",
            expiryTime = seconds, created = seconds, lastModified = seconds,
        )
        val edit = VaultEdit.Create("n", "W", fields)

        check(before.copy(entries = listOf(a, b, created)), edit, AppliedEdit("W"))
        fails("the new entry did not come out as asked", before.copy(entries = listOf(a, b, created.copy(groupId = "R", groupPath = emptyList()))), edit, AppliedEdit("W"))
        fails("the new entry is missing", before, edit, AppliedEdit("W"))
    }

    @Test
    fun aRecycledEntryIsInTheBinOtherwiseUnchanged() {
        val recycled = a.copy(groupPath = listOf("Recycle Bin"), groupId = "BIN", lastModified = seconds)
        val edit = VaultEdit.MoveToRecycleBin("a", a.lastModified)

        check(before.copy(entries = listOf(b), recycleBin = listOf(recycled)), edit, AppliedEdit("BIN"))
        fails("the entry did not move", before, edit, AppliedEdit("BIN"))
        fails("the moved entry changed", before.copy(entries = listOf(b), recycleBin = listOf(recycled.copy(title = "?"))), edit, AppliedEdit("BIN"))
    }

    @Test
    fun aRestoredEntryIsBackInItsGroupAndGroupsMadeOnTheWayAreAllowed() {
        val binned = a.copy(groupPath = listOf("Recycle Bin"), groupId = "BIN")
        val inBin = Vault(entries = listOf(b), groups = listOf(root, work), recycleBin = listOf(binned))
        val made = VaultGroup("M", "Old", listOf("Old"))
        val back = a.copy(groupPath = listOf("Old"), groupId = "M", lastModified = seconds)

        SaveCheck.verify(inBin, inBin.copy(entries = listOf(b, back), groups = listOf(root, work, made), recycleBin = emptyList()),
            VaultEdit.Restore("a", null), AppliedEdit("M", createdGroupIds = listOf("M")), now)
        assertFailsWith<SaveCheck.Failed> {
            SaveCheck.verify(inBin, inBin.copy(entries = listOf(b, back), groups = listOf(root, work, made), recycleBin = emptyList()),
                VaultEdit.Restore("a", null), AppliedEdit("M"), now)
        }
    }

    @Test
    fun theSettingsEntryAndTheBinAreEntriesLikeAnyOther() {
        val settings = Entry("s", title = Vault.SETTINGS_TITLE)
        val withSettings = before.copy(settings = settings, recycleBin = listOf(b.copy(id = "old", groupId = bin.id)))

        SaveCheck.verify(withSettings, withSettings.copy(entries = listOf(a.copy(notes = "n", lastModified = seconds), b)),
            VaultEdit.Update("a", null, EntryChanges(notes = "n")), AppliedEdit(null), now)
        assertFailsWith<SaveCheck.Failed> {
            SaveCheck.verify(withSettings, withSettings.copy(settings = null), VaultEdit.Update("a", null, EntryChanges()), AppliedEdit(null), now)
        }
    }
}
