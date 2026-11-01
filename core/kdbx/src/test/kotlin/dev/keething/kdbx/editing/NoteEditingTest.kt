package dev.keething.kdbx.editing

import dev.keething.domain.vault.EntryChanges
import dev.keething.domain.vault.EntryFieldTypes
import dev.keething.domain.vault.EntryType
import dev.keething.domain.vault.EntryTypeChange
import dev.keething.domain.vault.FieldValue
import dev.keething.kdbx.KeePassXcCli
import dev.keething.kdbx.reader.KdbxReader
import dev.keething.kdbx.tree.XElement
import dev.keething.kdbx.tree.XmlTree
import dev.keething.kdbx.xml.KdbxTime
import dev.keething.kdbx.xml.KdbxXmlReader
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A note in the file (keething-spec types.note AC1, AC8; kdbx.round-trip AC6 for Notes; the Mac `NoteTests`
 * "Writing the kind" and "Protection"): its kind's item, and Notes keeping their own protection through an edit.
 */
class NoteEditingTest {
    private val key = EntryType.CUSTOM_DATA_KEY
    private val now = Instant.ofEpochSecond(1_900_000_000)

    private fun tree(entries: String, meta: String = ""): XElement =
        XmlTree.parse("<KeePassFile><Meta>$meta</Meta><Root><Group><UUID>R</UUID><Name>P</Name>$entries</Group></Root></KeePassFile>".encodeToByteArray())

    private fun strings(vararg pairs: Pair<String, String>, protected: Set<String> = emptySet()): String =
        pairs.joinToString("") { (k, v) -> "<String><Key>$k</Key><Value${if (k in protected) " ProtectInMemory=\"True\"" else ""}>$v</Value></String>" }

    private val blank = arrayOf("Title" to "T", "UserName" to "", "Password" to "", "URL" to "")

    private fun XElement.entry(id: String) = assertNotNull(EntryEditing.entry(id, this))

    private fun XElement.dataKeys() = first("CustomData")?.all("Item")?.map { it.first("Key")?.text }

    private fun XElement.notes(): XElement? = all("String").firstOrNull { it.first("Key")?.text == "Notes" }?.first("Value")

    // The kind (AC1)

    @Test
    fun theKindIsWrittenAfterAutoTypeBeforeHistoryAsAnEditWithItsHistoryAndRemovingItTakesTheItemOut() {
        val root = tree(
            """
            <Entry><UUID>e</UUID><Times><LastModificationTime>2024-01-01T00:00:00Z</LastModificationTime></Times>
            ${strings(*blank, "Notes" to "x")}<AutoType><Enabled>True</Enabled></AutoType><History></History></Entry>
            """,
        )
        val entry = root.entry("e")

        EntryEditing.apply(EntryChanges(entryType = EntryTypeChange.Set(EntryType.NOTE)), entry, root, now)

        assertEquals(listOf("UUID", "Times", "String", "String", "String", "String", "String", "AutoType", "CustomData", "History"), entry.elements.map { it.name })
        assertEquals("note", entry.first("CustomData")?.first("Item")?.first("Value")?.text)
        assertEquals(1, entry.first("History")?.all("Entry")?.size, "a change of the kind is an edit, the old version into the history")
        assertNull(entry.first("History")?.first("Entry")?.first("CustomData"), "the old version has no kind")

        EntryEditing.apply(EntryChanges(entryType = EntryTypeChange.Remove), entry, root, now)
        assertNull(entry.first("CustomData"))
        assertEquals(2, entry.first("History")?.all("Entry")?.size)
    }

    @Test
    fun theKindLivesBesideTheFieldTypesAndOtherAppsItemsAndAKindItDoesNotKnowSurvivesAnEdit() {
        val root = tree(
            """
            <Entry><UUID>e</UUID><Times><LastModificationTime>2024-01-01T00:00:00Z</LastModificationTime></Times>
            ${strings(*blank, "Notes" to "x")}<AutoType><Enabled>True</Enabled></AutoType>
            <CustomData><Item><Key>KPXC_X</Key><Value>1</Value></Item><Item><Key>$key</Key><Value>checklist</Value></Item></CustomData>
            <History></History></Entry>
            <Entry><UUID>f</UUID>${strings(*blank, "Notes" to "x")}<AutoType><Enabled>True</Enabled></AutoType>
            <CustomData><Item><Key>KPXC_X</Key><Value>1</Value></Item></CustomData><History></History></Entry>
            """,
        )
        val unknown = root.entry("e")
        EntryEditing.apply(EntryChanges(title = "Other"), unknown, root, now)
        assertEquals(listOf("1", "checklist"), unknown.first("CustomData")?.all("Item")?.map { it.first("Value")?.text })

        val other = root.entry("f")
        EntryEditing.apply(EntryChanges(fieldTypes = mapOf("a" to "date"), entryType = EntryTypeChange.Set(EntryType.NOTE)), other, root, now)
        assertEquals(1, other.all("CustomData").size)
        assertEquals(listOf("KPXC_X", EntryFieldTypes.CUSTOM_DATA_KEY, key), other.dataKeys(), "other apps' items stay; one CustomData")
        val out = java.io.ByteArrayOutputStream().also { XmlTree.write(root, it, protect = null) }
        val again = KdbxXmlReader.read(out.toByteArray()).entries
        assertEquals(listOf("checklist", "note"), again.map { it.customData[key] })
        assertEquals("""{"a":"date"}""", again.last().customData[EntryFieldTypes.CUSTOM_DATA_KEY])
    }

    @Test
    fun theKindsItemKeepsItsLastModificationTimeUpToDate() {
        val root = tree(
            """
            <Entry><UUID>e</UUID>${strings(*blank, "Notes" to "x")}
            <CustomData><Item><Key>$key</Key><Value>Note</Value><LastModificationTime>2024-01-01T00:00:00Z</LastModificationTime></Item></CustomData></Entry>
            """,
        )
        val entry = root.entry("e")

        EntryEditing.apply(EntryChanges(entryType = EntryTypeChange.Set(EntryType.NOTE)), entry, root, now)

        val item = assertNotNull(entry.first("CustomData")?.first("Item"))
        assertEquals("note", item.first("Value")?.text)
        assertEquals(KdbxTime.iso(now), item.first("LastModificationTime")?.text)
    }

    // Protection (kdbx.round-trip AC6 for Notes)

    @Test
    fun notesKeepTheirOwnProtectionThroughAnEditAskedForItIsWrittenOffIsKept() {
        val root = tree(
            """
            <Entry><UUID>p</UUID><Times><LastModificationTime>2024-01-01T00:00:00Z</LastModificationTime></Times>
            ${strings(*blank, "Notes" to "secret text", protected = setOf("Notes"))}</Entry>
            <Entry><UUID>q</UUID>${strings(*blank, "Notes" to "plain")}</Entry>
            <Entry><UUID>r</UUID>${strings(*blank)}</Entry>
            """,
            meta = "<MemoryProtection><ProtectNotes>False</ProtectNotes></MemoryProtection>",
        )
        val p = root.entry("p")
        EntryEditing.apply(EntryChanges(notes = "changed text"), p, root, now)
        assertEquals("changed text", p.notes()?.secret?.reveal(), "an edit of the text alone keeps it protected")

        EntryEditing.apply(EntryChanges(notesProtected = false), p, root, now)
        assertNull(p.notes()?.secret, "turned off")
        assertEquals("changed text", p.notes()?.text, "the text untouched")
        EntryEditing.apply(EntryChanges(notes = "again"), p, root, now)
        assertNull(p.notes()?.secret, "and kept off")
        assertEquals("again", p.notes()?.text)
        assertEquals(3, p.first("History")?.all("Entry")?.size)
        assertEquals("changed text", p.first("History")?.all("Entry")?.get(1)?.notes()?.secret?.reveal(), "the protected version kept so in the history")

        val q = root.entry("q")
        EntryEditing.apply(EntryChanges(notesProtected = true), q, root, now)
        assertEquals("plain", q.notes()?.secret?.reveal())

        val r = root.entry("r")
        EntryEditing.apply(EntryChanges(notes = "new"), r, root, now)
        assertNull(r.notes()?.secret, "a new value takes Meta/MemoryProtection's")
        assertEquals("new", r.notes()?.text)
    }

    @Test
    fun underProtectNotesANewValueIsProtectedAndAnUnprotectedOneStaysAsItIs() {
        val root = tree(
            """
            <Entry><UUID>q</UUID>${strings(*blank, "Notes" to "plain")}</Entry>
            <Entry><UUID>r</UUID>${strings(*blank)}</Entry>
            """,
            meta = "<MemoryProtection><ProtectNotes>True</ProtectNotes></MemoryProtection>",
        )
        val q = root.entry("q")
        EntryEditing.apply(EntryChanges(notes = "still plain"), q, root, now)
        assertNull(q.notes()?.secret, "its own protection, not the database's")
        assertEquals("still plain", q.notes()?.text)

        val r = root.entry("r")
        EntryEditing.apply(EntryChanges(notes = "new"), r, root, now)
        assertEquals("new", r.notes()?.secret?.reveal())
        EntryEditing.apply(EntryChanges(notesProtected = false, notes = "open"), r, root, now)
        assertEquals("open", r.notes()?.text, "asked for, the database's does not count")
        assertNull(r.notes()?.secret)
    }

    // KeePassXC (AC8, by its command-line tool)

    @Test
    fun keePassXcShowsAndExportsANoteTheCoreWroteAndAfterItsOwnEditTheNoteIsStillOneAndProtected() {
        if (!KeePassXcCli.isInstalled) return
        val folder = Files.createTempDirectory("kdbx-notes").toFile()
        try {
            val editable = EditingFixtures.open("kdbx4-aes-argon2d")
            val id = EditingFixtures.document("kdbx4-aes-argon2d").entry("Second renamed").uuid
            val text = "Dune\nArrival\nthe one with the sandworms"
            val changes = EntryChanges(
                title = "Films", username = "", url = "", notes = text, notesProtected = true,
                entryType = EntryTypeChange.Set(EntryType.NOTE),
            )
            EntryEditing.apply(changes, assertNotNull(EntryEditing.entry(id, editable.root)), editable.root, now)
            val file = File(folder, "note.kdbx").apply { writeBytes(EditingFixtures.write(editable)) }

            val shown = KeePassXcCli.run("show", "-s", "-a", "Notes", file.path, "Films")
            assertEquals(0, shown.code, shown.err)
            assertEquals(text, shown.text.trimEnd('\n'), "KeePassXC reads the text")

            val exported = KeePassXcCli.run("export", "-f", "xml", file.path)
            assertEquals(0, exported.code, exported.err)
            val xml = exported.text.replace(Regex(">\\s+<"), "><")
            val element = xml.substring(xml.lastIndexOf("<Entry>", xml.indexOf("<Value>Films</Value>"))).substringBefore("</Entry>")
            assertTrue("<Key>Notes</Key><Value ProtectInMemory=\"True\">" in element, "Notes listed as protected")
            assertTrue("<Key>$key</Key><Value>note</Value>" in element, "the kind kept as plugin data")

            val edited = KeePassXcCli.run("edit", "-t", "Films seen", file.path, "Films")
            assertEquals(0, edited.code, edited.err)
            val reread = KdbxReader.open(file.readBytes(), EditingFixtures.key).document.entries.single { it.uuid == id }
            assertEquals("note", reread.customData[key], "KeePassXC's edit kept the kind")
            val notes = assertIs<FieldValue.Protected>(reread.fields.last { it.key == "Notes" }.value, "and the protection")
            assertEquals(text, notes.secret.reveal())
        } finally {
            folder.deleteRecursively()
        }
    }

}
