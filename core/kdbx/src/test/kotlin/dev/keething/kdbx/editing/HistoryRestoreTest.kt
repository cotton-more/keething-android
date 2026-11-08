package dev.keething.kdbx.editing

import dev.keething.domain.vault.VaultEdit
import dev.keething.kdbx.editing.EditingFixtures.open
import dev.keething.kdbx.editing.EditingFixtures.reread
import dev.keething.kdbx.tree.XElement
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * An old version of an entry restored in the file (keething-spec edit.history-restore; the Mac `HistoryTests`, "the
 * oldest version can be restored from a full history"). "Second renamed" has a history in every fixture.
 */
class HistoryRestoreTest {
    private val now = Instant.ofEpochSecond(1_950_000_000)

    private class Opened(val editable: EditableDatabase, val id: String, val entry: XElement)

    private fun second(fixture: String = "kdbx4-aes-aeskdf"): Opened {
        val editable = open(fixture)
        val id = reread(editable).entry("Second renamed").uuid
        return Opened(editable, id, assertNotNull(EntryEditing.entry(id, editable.root)))
    }

    private fun XElement.history(): List<XElement> = first("History")?.all("Entry").orEmpty()

    private fun XElement.string(key: String): String? =
        all("String").firstOrNull { it.first("Key")?.text == key }?.first("Value")?.let { it.secret?.reveal() ?: it.text }

    private fun XElement.time(name: String): String? = first("Times")?.first(name)?.text

    // spec: edit.history-restore/AC3
    @Test
    fun theEntryTakesAllOfTheVersionAndKeepsItsUuidAndCreationTime() {
        val opened = second()
        val editable = opened.editable
        val id = opened.id
        val entry = opened.entry
        val version = entry.history().first()
        // What the version holds beside its fields: an icon, colours, auto-type, custom data, tags, an expiry.
        fun set(name: String, text: String, parent: XElement = version) {
            val existing = parent.first(name)
            if (existing != null) existing.text = text else parent.append(XElement(name, text))
        }
        set("IconID", "42")
        set("ForegroundColor", "#FF0000")
        set("BackgroundColor", "#00FF00")
        set("Tags", "old;tags")
        version.first("Times")?.let { times ->
            set("Expires", "True", times)
            set("ExpiryTime", EntryLifecycle.time(Instant.ofEpochSecond(1_800_000_000), EntryLifecycle.usesIsoTimes(editable.root)), times)
        }
        set("Enabled", "False", assertNotNull(version.first("AutoType"), "the fixture's version has auto-type"))
        val data = version.first("CustomData") ?: XElement("CustomData").also { version.insertAfter(it, version.first("AutoType")) }
        data.append(XElement("Item").apply { append(XElement("Key", "Old.Key")); append(XElement("Value", "kept")) })
        val title = version.string("Title")
        val password = version.string("Password")
        val created = entry.time("CreationTime")
        val versions = entry.history().size

        editable.apply(VaultEdit.RestoreVersion(id, null, 0, null), now)

        assertEquals(id, entry.first("UUID")?.text?.trim())
        assertEquals("UUID", entry.elements.first().name, "the UUID first")
        assertEquals("History", entry.elements.last().name, "the history last")
        assertEquals(title, entry.string("Title"))
        assertEquals(password, entry.string("Password"))
        assertEquals("42", entry.first("IconID")?.text)
        assertEquals("#FF0000", entry.first("ForegroundColor")?.text)
        assertEquals("#00FF00", entry.first("BackgroundColor")?.text)
        assertEquals("old;tags", entry.first("Tags")?.text)
        assertEquals("False", entry.first("AutoType")?.first("Enabled")?.text)
        assertEquals("kept", entry.first("CustomData")?.all("Item")?.last()?.first("Value")?.text)
        assertEquals("True", entry.time("Expires"))
        assertEquals(created, entry.time("CreationTime"), "the entry keeps its creation time")
        assertEquals(EntryLifecycle.time(now, EntryLifecycle.usesIsoTimes(editable.root)), entry.time("LastModificationTime"))
        assertEquals(versions + 1, entry.history().size)

        val written = reread(editable).entries.single { it.uuid == id }
        assertEquals(now, written.lastModified)
        assertEquals(Instant.ofEpochSecond(1_800_000_000), written.expiryTime)
        editable.wipe()
    }

    // spec: edit.history-restore/AC2
    @Test
    fun theOldestVersionOfAFullHistoryComesBack() {
        val opened = second()
        val entry = opened.entry
        val count = entry.history().size
        val meta = assertNotNull(opened.editable.root.first("Meta"))
        meta.first("HistoryMaxItems")?.let(meta::remove)
        meta.append(XElement("HistoryMaxItems", count.toString()))
        val oldest = entry.history().first().string("Title")
        val current = entry.string("Title")

        opened.editable.apply(VaultEdit.RestoreVersion(opened.id, null, 0, null), now)

        assertEquals(oldest, entry.string("Title"))
        assertEquals(count, entry.history().size, "trimmed back to the maximum")
        assertEquals(current, entry.history().last().string("Title"), "the version it replaced is the newest kept")
        opened.editable.wipe()
    }

    // spec: edit.history-restore/AC4
    @Test
    fun theReplacedVersionIsInTheHistoryAndRestoringItUndoesTheRestore() {
        val opened = second("kdbx4-chacha20-argon2id")
        val before = reread(opened.editable).entries.single { it.uuid == opened.id }

        opened.editable.apply(VaultEdit.RestoreVersion(opened.id, null, 0, null), now)
        val newest = opened.entry.history().lastIndex
        assertEquals(before.field("Title"), plain(assertNotNull(opened.entry.history()[newest].string("Title"))))
        opened.editable.apply(VaultEdit.RestoreVersion(opened.id, null, newest, null), now.plusSeconds(60))

        val after = reread(opened.editable).entries.single { it.uuid == opened.id }
        assertEquals(before.fields, after.fields)
        assertEquals(before.attachments, after.attachments)
        assertEquals(before.tags, after.tags)
        assertEquals(before.created, after.created)
        opened.editable.wipe()
    }

    @Test
    fun aVersionNotThereAndAnEntryInTheRecycleBinAreRefusedWithNothingChanged() {
        val opened = second()
        val count = opened.entry.history().size
        assertFailsWith<EditException.VersionNotFound> { opened.editable.apply(VaultEdit.RestoreVersion(opened.id, null, count, null), now) }
        assertFailsWith<EditException.VersionNotFound> { opened.editable.apply(VaultEdit.RestoreVersion(opened.id, null, -1, null), now) }
        assertEquals(count, opened.entry.history().size, "no snapshot taken")

        EntryLifecycle.moveToRecycleBin(opened.id, opened.editable.root, now)
        assertFailsWith<EditException.AlreadyInRecycleBin> { opened.editable.apply(VaultEdit.RestoreVersion(opened.id, null, 0, null), now) }
        assertEquals(count, opened.entry.history().size)
        assertNull(opened.entry.history().firstOrNull { it.first("UUID")?.text?.trim() != opened.id }, "the history is the entry's own")
        opened.editable.wipe()
    }
}
