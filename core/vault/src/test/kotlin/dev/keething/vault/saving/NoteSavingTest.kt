package dev.keething.vault.saving

import dev.keething.domain.secret.Secret
import dev.keething.domain.totp.TotpConfig
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.EntryChanges
import dev.keething.domain.vault.EntryType
import dev.keething.domain.vault.EntryTypeChange
import dev.keething.domain.vault.GroupDestination
import dev.keething.domain.vault.NoteTitle
import dev.keething.domain.vault.TotpChange
import dev.keething.domain.vault.Vault
import dev.keething.domain.vault.VaultEdit
import dev.keething.domain.storage.DatabaseBackups
import dev.keething.kdbx.editing.NoteFiles
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.totp.TotpSetup
import dev.keething.vault.opening.OpenResult
import dev.keething.vault.opening.VaultOpener
import dev.keething.vault.session.FakeStore
import dev.keething.vault.session.fixtureBytes
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.w3c.dom.Element

/**
 * Notes through the save and its check (keething-spec types.note AC1, AC8; kdbx.round-trip AC6 for Notes; the Mac
 * `NoteTests` saver expectations). Every file written here is checked against KDBX_XML.xsd (SchemaCheck).
 */
class NoteSavingTest {
    private val now = Instant.ofEpochSecond(1_900_000_000)

    private val backups = object : DatabaseBackups {
        override fun keep(database: ByteArray, fileName: String, at: Instant) {}
    }

    private fun key() = DatabaseKey.of(Secret.of("pw"))

    private fun unlock(store: FakeStore): Vault = assertIs<OpenResult.Opened>(VaultOpener().open(store, key())).opened.vault

    private fun saved(result: SaveResult): Vault = assertIs<SaveResult.Saved>(result).opened.vault

    private fun Vault.entry(id: String): Entry = entries.single { it.id == id }

    private fun element(store: FakeStore, id: String): Element {
        val xml = NoteFiles.entryXml(store.content!!, key(), id)
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream()).documentElement
    }

    private fun Element.children(name: String? = null): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { name == null || it.tagName == name }

    private fun Element.child(name: String): Element? = children(name).firstOrNull()

    private fun Element.notesValue(): Element? = children("String").firstOrNull { it.child("Key")?.textContent == "Notes" }?.child("Value")

    @Test
    fun aNoteMadeItsTextEditedItsTitleEditedItsProtectionTurnedOffEachSaveChecked() {
        val store = FakeStore(fixtureBytes("kdbx4-aes-argon2d"))
        val saver = VaultSaver(store, backups)
        val top = unlock(store).groups.single { it.isRoot }
        val id = Entry.newId()
        val text = "Dune\nArrival\nthe one with the sandworms"
        val fields = EntryChanges(
            title = NoteTitle.make(text, protected = true), notes = text, notesProtected = true,
            entryType = EntryTypeChange.Set(EntryType.NOTE),
        )

        var note = saved(saver.save(VaultEdit.Create(id, GroupDestination(top.id), fields), key(), now)).entry(id)
        assertTrue(note.isNote && note.notesProtected)
        assertEquals("Note", note.title)
        assertEquals(text, note.notes)
        assertEquals(note, unlock(store).entry(id), "as the file has it")

        // In the file: Notes protected; nothing but KeePassXC's five strings and one item (AC8).
        val element = element(store, id)
        assertEquals(
            listOf("UUID", "IconID", "ForegroundColor", "BackgroundColor", "OverrideURL", "Tags", "Times", "String", "String", "String", "String", "String", "AutoType", "CustomData", "History"),
            element.children().map { it.tagName },
        )
        assertEquals(listOf("Notes", "Password", "Title", "URL", "UserName"), element.children("String").map { it.child("Key")?.textContent })
        assertEquals("True", element.notesValue()?.getAttribute("Protected"), "written protected")
        assertEquals(
            listOf("${EntryType.CUSTOM_DATA_KEY}=note"),
            element.child("CustomData")!!.children("Item").map { "${it.child("Key")?.textContent}=${it.child("Value")?.textContent}" },
        )
        assertFalse("sandworms" in NoteFiles.entryXml(store.content!!, key(), id), "the text is not in the XML in the clear")

        note = saved(saver.save(VaultEdit.Update(id, note.lastModified, EntryChanges(notes = text + "\nBlade Runner")), key(), now)).entry(id)
        assertTrue(note.notesProtected, "kept protected through an edit of the text")
        note = saved(saver.save(VaultEdit.Update(id, note.lastModified, EntryChanges(title = "Films")), key(), now)).entry(id)
        assertTrue(note.notesProtected, "and through an edit of the title alone")
        assertEquals(text + "\nBlade Runner", note.notes)

        note = saved(saver.save(VaultEdit.Update(id, note.lastModified, EntryChanges(notesProtected = false)), key(), now)).entry(id)
        assertTrue(!note.notesProtected && note.isNote)
        assertEquals(3, element(store, id).child("History")!!.children("Entry").size)
        assertEquals("", element(store, id).notesValue()?.getAttribute("Protected"))

        note = saved(saver.save(VaultEdit.Update(id, note.lastModified, EntryChanges(entryType = EntryTypeChange.Remove)), key(), now)).entry(id)
        assertNull(note.entryType)
        assertTrue(note.isNote, "still one by the rule")
        assertNull(element(store, id).child("CustomData"))
    }

    @Test
    fun anEntryReadAsANoteByTheRuleIsLeftByteForByteByASaveOfAnotherEntry() {
        val store = FakeStore(fixtureBytes("kdbx4-aes-argon2d"))
        val saver = VaultSaver(store, backups)
        val top = unlock(store).groups.single { it.isRoot }
        val imported = Entry.newId()
        val other = Entry.newId()
        saved(saver.save(VaultEdit.Create(imported, GroupDestination(top.id), EntryChanges(title = "From 1Password", notes = "Dune")), key(), now))
        saved(saver.save(VaultEdit.Create(other, GroupDestination(top.id), EntryChanges(title = "Login", username = "me")), key(), now))
        val vault = unlock(store)
        val note = vault.entry(imported)
        assertTrue(note.isNote && note.entryType == null, "read as a note, with no kind")
        val before = NoteFiles.entryXml(store.content!!, key(), imported)

        val login = vault.entry(other)
        saved(saver.save(VaultEdit.Update(other, login.lastModified, EntryChanges(password = Secret.of("p"))), key(), now))

        assertEquals(before, NoteFiles.entryXml(store.content!!, key(), imported), "reading it as a note wrote nothing")
    }

    @Test
    fun aDatabaseThatProtectsNotesGivesANewEntryProtectedNotesAndTheCheckExpectsIt() {
        val store = FakeStore(NoteFiles.protectingNotes(fixtureBytes("kdbx4-aes-argon2d"), key()))
        val saver = VaultSaver(store, backups)
        val top = unlock(store).groups.single { it.isRoot }

        val login = saved(saver.save(VaultEdit.Create(Entry.newId(), GroupDestination(top.id), EntryChanges(title = "Login", notes = "n")), key(), now))
            .entries.single { it.title == "Login" }
        assertTrue(login.notesProtected)
        val bare = saved(saver.save(VaultEdit.Create(Entry.newId(), GroupDestination(top.id), EntryChanges(title = "Bare")), key(), now))
            .entries.single { it.title == "Bare" }
        assertTrue(bare.notesProtected, "its empty Notes too, as KeePassXC writes them")
        val plain = saved(
            saver.save(VaultEdit.Create(Entry.newId(), GroupDestination(top.id), EntryChanges(title = "Plain", notes = "n", notesProtected = false)), key(), now),
        ).entries.single { it.title == "Plain" }
        assertFalse(plain.notesProtected, "asked for, the database's does not count")
    }

    @Test
    fun notesWrittenWhereAnEntryHadNoneTakeTheDatabasesProtectionAndTheCheckExpectsIt() {
        val first = unlock(FakeStore(fixtureBytes("kdbx4-aes-argon2d"))).entries.first()
        val store = FakeStore(NoteFiles.protectingNotes(NoteFiles.withoutNotes(fixtureBytes("kdbx4-aes-argon2d"), key(), first.id), key()))
        val saver = VaultSaver(store, backups)
        val entry = unlock(store).entry(first.id)
        assertFalse(entry.notesProtected)

        val titled = saved(saver.save(VaultEdit.Update(entry.id, entry.lastModified, EntryChanges(title = "Renamed")), key(), now)).entry(entry.id)
        assertFalse(titled.notesProtected, "no Notes written, none made")
        assertNull(element(store, entry.id).notesValue())

        val noted = saved(saver.save(VaultEdit.Update(entry.id, titled.lastModified, EntryChanges(notes = "n")), key(), now)).entry(entry.id)
        assertTrue(noted.notesProtected)
    }

    /** What a received entry's changes ask (core:sharing `Receiving.changes`, sharing.receiving AC9). */
    // spec: sharing.receiving/AC9
    @Test
    fun aReceivedNoteIsWrittenAsSentItsKindAsStoredAndTheCheckExpectsIt() {
        val store = FakeStore(fixtureBytes("kdbx4-aes-argon2d"))
        val saver = VaultSaver(store, backups)
        val top = unlock(store).groups.single { it.isRoot }
        val films = Entry.newId()
        val list = Entry.newId()
        val received = EntryChanges(title = "Films to watch", notes = "Dune\nArrival", notesProtected = true, entryType = EntryTypeChange.Stored("note"))

        saved(saver.save(VaultEdit.Create(films, GroupDestination(top.id), received), key(), now))
        val vault = saved(
            saver.save(VaultEdit.Create(list, GroupDestination(top.id), EntryChanges(title = "Groceries", notes = "milk", entryType = EntryTypeChange.Stored(" Checklist "))), key(), now),
        )

        val note = vault.entry(films)
        assertTrue(note.isNote && note.notesProtected)
        assertEquals("True", element(store, films).notesValue()?.getAttribute("Protected"))
        assertEquals("note", element(store, films).child("CustomData")!!.children("Item").single().child("Value")?.textContent)
        assertEquals(" Checklist ", vault.entry(list).entryType, "a kind this version does not know, kept as it was stored")
        assertFalse(vault.entry(list).isNote)
        assertEquals(vault.entry(list), unlock(store).entry(list))
    }

    @Test
    fun aCodeSetAndRemovedChangesWhetherTheEntryHasACodesFieldsAndTheCheckExpectsIt() {
        val store = FakeStore(fixtureBytes("kdbx4-aes-argon2d"))
        val saver = VaultSaver(store, backups)
        val entry = unlock(store).entries.first()
        val code = TotpConfig(Secret.of("12345678901234567890"))

        val coded = saved(
            saver.save(VaultEdit.Update(entry.id, entry.lastModified, EntryChanges(totp = TotpChange.Set(code, TotpSetup.otpauthUri(code, entry.title, entry.username)))), key(), now),
        ).entry(entry.id)
        assertTrue(coded.hasCodeFields)
        val removed = saved(saver.save(VaultEdit.Update(entry.id, coded.lastModified, EntryChanges(totp = TotpChange.Remove)), key(), now)).entry(entry.id)
        assertFalse(removed.hasCodeFields)
    }
}
