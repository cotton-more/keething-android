package dev.keething.vault.saving

import dev.keething.domain.secret.Secret
import dev.keething.domain.storage.DatabaseBackups
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.EntryChanges
import dev.keething.domain.vault.EntryType
import dev.keething.domain.vault.EntryTypeChange
import dev.keething.domain.vault.GroupDestination
import dev.keething.domain.vault.NewGroup
import dev.keething.domain.vault.QuickNote
import dev.keething.domain.vault.Vault
import dev.keething.domain.vault.VaultEdit
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.vault.opening.OpenResult
import dev.keething.vault.opening.VaultOpener
import dev.keething.vault.session.FakeStore
import dev.keething.vault.session.fixtureBytes
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A quick note through the save (keething-spec app.quick-note AC2, groups.new-groups AC3; the Mac's `QuickNoteTests`,
 * "saver"): the Inbox made with the note, or the one another app made since the read taken, never a second.
 */
class QuickNoteSavingTest {
    private val now = Instant.ofEpochSecond(1_900_000_000)

    private val backups = object : DatabaseBackups {
        override fun keep(database: ByteArray, fileName: String, at: Instant) {}
    }

    private fun key() = DatabaseKey.of(Secret.of("pw"))

    private fun unlock(store: FakeStore): Vault = assertIs<OpenResult.Opened>(VaultOpener().open(store, key())).opened.vault

    private fun saved(result: SaveResult): Vault = assertIs<SaveResult.Saved>(result).opened.vault

    /** What the quick note's editor saves (`EntryEditor.createQuickNote`): a protected note. */
    private fun note(text: String) = EntryChanges(
        title = "Note, Nov 1, 2026", notes = text, notesProtected = true, entryType = EntryTypeChange.Set(EntryType.NOTE),
    )

    private fun Vault.topLevelInboxes() = groups.filter { it.path.size == 1 && it.name.trim().lowercase() == "inbox" }

    @Test
    fun noInboxSoItIsMadeAtTheTopLevelWithTheNote() {
        val store = FakeStore(fixtureBytes("kdbx4-aes-argon2d"))
        val saver = VaultSaver(store, backups)
        val read = unlock(store)
        assertTrue(read.topLevelInboxes().isEmpty(), "the fixture has no Inbox")
        val top = read.groups.single { it.isRoot }
        val id = Entry.newId()
        val made = GroupDestination(top.id, listOf(NewGroup(Entry.newId(), QuickNote.INBOX)))

        val after = saved(saver.save(VaultEdit.Create(id, made, note("Dune")), key(), now))

        val inbox = after.topLevelInboxes().single()
        assertEquals(made.finalGroupId, inbox.id)
        assertEquals("Inbox", inbox.name)
        val saved = after.entries.single { it.id == id }
        assertEquals(inbox.id, saved.groupId)
        assertTrue(saved.isNote && saved.notesProtected)
        assertEquals("Dune", saved.notes)
        assertEquals(after, unlock(store), "as the file has it")
    }

    @Test
    fun anInboxMadeByAnotherAppSinceTheReadIsTakenNotMadeTwice() {
        val store = FakeStore(fixtureBytes("kdbx4-aes-argon2d"))
        val saver = VaultSaver(store, backups)
        val top = unlock(store).groups.single { it.isRoot }
        // The editor, on the read taken before, would make one.
        val stale = GroupDestination(top.id, listOf(NewGroup(Entry.newId(), QuickNote.INBOX)))
        // Meanwhile another app (another device, through Syncthing) made one.
        val elsewhere = NewGroup(Entry.newId(), "INBOX")
        val elsewhereStore = FakeStore(store.content!!)
        saved(VaultSaver(elsewhereStore, backups).save(VaultEdit.CreateGroup(top.id, elsewhere), key(), now))
        store.change(elsewhereStore.content!!)

        val id = Entry.newId()
        val after = saved(saver.save(VaultEdit.Create(id, stale, note("Arrival")), key(), now))

        assertEquals(listOf(elsewhere.id), after.topLevelInboxes().map { it.id }, "no second Inbox")
        assertEquals(elsewhere.id, after.entries.single { it.id == id }.groupId, "the note went into the one made elsewhere")
        assertEquals(after, unlock(store))
    }

    @Test
    fun aNewGroupPathMeetsTheFileAsItIsNowLevelByLevel() {
        val store = FakeStore(fixtureBytes("kdbx4-aes-argon2d"))
        val saver = VaultSaver(store, backups)
        val top = unlock(store).groups.single { it.isRoot }
        // Asked for: Trips / 2026 / Rome, all new. Meanwhile someone made "trips" and "2026" in it.
        val asked = GroupDestination(top.id, listOf(NewGroup(Entry.newId(), "Trips"), NewGroup(Entry.newId(), "2026"), NewGroup(Entry.newId(), "Rome")))
        val trips = NewGroup(Entry.newId(), "trips")
        val year = NewGroup(Entry.newId(), "2026")
        val elsewhereStore = FakeStore(store.content!!)
        val elsewhere = VaultSaver(elsewhereStore, backups)
        saved(elsewhere.save(VaultEdit.CreateGroup(top.id, trips), key(), now))
        saved(elsewhere.save(VaultEdit.CreateGroup(trips.id, year), key(), now))
        store.change(elsewhereStore.content!!)

        val id = Entry.newId()
        val after = saved(saver.save(VaultEdit.Create(id, asked, EntryChanges(title = "Hotel")), key(), now))

        val rome = after.groups.single { it.path == listOf("trips", "2026", "Rome") }
        assertEquals(asked.newGroups[2].id, rome.id, "only the level still missing is made")
        assertEquals(1, after.groups.count { it.path.size == 1 && it.name.lowercase() == "trips" })
        assertEquals(rome.id, after.entries.single { it.id == id }.groupId)
    }
}
