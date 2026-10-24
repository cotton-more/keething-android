package dev.keething.kdbx.editing

import dev.keething.domain.vault.EntryChanges
import dev.keething.domain.vault.EntryFieldTypes
import dev.keething.kdbx.KeePassXcCli
import dev.keething.kdbx.tree.XElement
import dev.keething.kdbx.tree.XmlTree
import dev.keething.kdbx.xml.KdbxDocument
import dev.keething.kdbx.xml.KdbxXmlReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The field types in an entry's `CustomData` (the Mac `FieldTypeTests` "Writing" expectations). */
class FieldTypesEditingTest {
    private val key = EntryFieldTypes.CUSTOM_DATA_KEY
    private val former = EntryFieldTypes.FORMER_CUSTOM_DATA_KEY
    private val now = Instant.ofEpochSecond(1_900_000_000)

    private fun item(key: String, value: String) = "<Item><Key>$key</Key><Value>$value</Value></Item>"

    private fun tree(entries: String): XElement =
        XmlTree.parse("<KeePassFile><Root><Group><UUID>R</UUID><Name>P</Name>$entries</Group></Root></KeePassFile>".encodeToByteArray())

    private fun reread(root: XElement): KdbxDocument {
        val out = ByteArrayOutputStream()
        XmlTree.write(root, out, protect = null)
        return KdbxXmlReader.read(out.toByteArray())
    }

    private fun XElement.entry(id: String) = assertNotNull(EntryEditing.entry(id, this))

    private fun XElement.dataKeys() = first("CustomData")?.all("Item")?.map { it.first("Key")?.text }

    @Test
    fun theTypesGoIntoOneItemAfterAutoTypeBeforeHistoryAndEmptyTakesItOut() {
        val root = tree(
            """
            <Entry><UUID>e</UUID><Times><LastModificationTime>2024-01-01T00:00:00Z</LastModificationTime></Times>
            <String><Key>Title</Key><Value>T</Value></String><AutoType><Enabled>True</Enabled></AutoType>
            <History></History></Entry>
            """,
        )
        val entry = root.entry("e")

        EntryEditing.apply(EntryChanges(fieldTypes = mapOf("Ablauf" to "until")), entry, root, now)

        assertEquals(listOf("UUID", "Times", "String", "AutoType", "CustomData", "History"), entry.elements.map { it.name })
        assertEquals(listOf(key), entry.dataKeys())
        assertEquals("""{"Ablauf":"until"}""", entry.first("CustomData")?.first("Item")?.first("Value")?.text)
        assertEquals(1, entry.first("History")?.all("Entry")?.size, "a change of types is an edit, with its history")

        EntryEditing.apply(EntryChanges(fieldTypes = mapOf("Ablauf" to "date")), entry, root, now)
        assertEquals(1, entry.all("CustomData").size)
        assertEquals("""{"Ablauf":"date"}""", entry.first("CustomData")?.first("Item")?.first("Value")?.text)

        EntryEditing.apply(EntryChanges(fieldTypes = emptyMap()), entry, root, now)
        assertNull(entry.first("CustomData"), "its last item gone, the element goes too")
    }

    @Test
    fun withoutAutoTypeOrHistoryTheElementGoesAtTheEnd() {
        val root = tree("<Entry><UUID>e</UUID><String><Key>Title</Key><Value>T</Value></String></Entry>")
        val entry = root.entry("e")

        EntryEditing.write(EntryChanges(fieldTypes = mapOf("a" to "url")), entry, root, now)

        assertEquals(listOf("UUID", "Times", "String", "CustomData"), entry.elements.map { it.name })
    }

    @Test
    fun otherAppsItemsStay() {
        val root = tree(
            """<Entry><UUID>k</UUID><String><Key>Title</Key><Value>K</Value></String><CustomData>${item("KPXC_X", "1")}</CustomData></Entry>""",
        )
        val entry = root.entry("k")

        EntryEditing.apply(EntryChanges(fieldTypes = mapOf("a" to "date")), entry, root, now)
        assertEquals(listOf("KPXC_X", key), entry.dataKeys())

        EntryEditing.apply(EntryChanges(fieldTypes = emptyMap()), entry, root, now)
        assertEquals(listOf("KPXC_X"), entry.dataKeys())
        assertEquals(listOf(mapOf("KPXC_X" to "1")), reread(root).entries.map { it.customData })
    }

    @Test
    fun anItemsModificationTimeIsKeptUpToDateInItsOwnForm() {
        val root = tree(
            """
            <Entry><UUID>a</UUID><CustomData><Item><Key>$key</Key><Value>{}</Value><LastModificationTime>2024-01-01T00:00:00Z</LastModificationTime></Item></CustomData></Entry>
            <Entry><UUID>b</UUID><CustomData><Item><Key>$key</Key><Value>{}</Value><LastModificationTime>AAAAAAAAAAA=</LastModificationTime></Item></CustomData></Entry>
            """,
        )

        EntryEditing.apply(EntryChanges(fieldTypes = mapOf("x" to "code")), root.entry("a"), root, now)
        EntryEditing.apply(EntryChanges(fieldTypes = mapOf("x" to "code")), root.entry("b"), root, now)

        fun time(id: String) = root.entry(id).first("CustomData")?.first("Item")?.first("LastModificationTime")?.text
        assertEquals("2030-03-17T17:46:40Z", time("a"))
        assertEquals(dev.keething.kdbx.xml.KdbxTime.base64(now), time("b"))
    }

    @Test
    fun theFormerNameIsReadWhileThereIsNoNewItemAndGoesWithTheNextWriteOfTheTypes() {
        val root = tree(
            """
            <Entry><UUID>old</UUID><CustomData>${item(former, """{"Ablauf":"until"}""")}</CustomData></Entry>
            <Entry><UUID>both</UUID><CustomData>${item(former, """{"A":"until"}""")}${item(key, """{"A":"date"}""")}</CustomData></Entry>
            """,
        )
        val entry = root.entry("old")

        EntryEditing.apply(EntryChanges(fieldTypes = mapOf("Ablauf" to "date")), entry, root, now)
        assertEquals(listOf(key), entry.dataKeys())

        EntryEditing.apply(EntryChanges(title = "T"), entry, root, now)
        assertEquals(listOf(key), entry.dataKeys(), "other edits leave it")

        val both = root.entry("both")
        EntryEditing.apply(EntryChanges(fieldTypes = emptyMap()), both, root, now)
        assertNull(both.first("CustomData"), "no types: both items go")
    }

    @Test
    fun keePassXcReadsTheWrittenFileAndKeepsTheItem() {
        if (!KeePassXcCli.isInstalled) return
        val folder = Files.createTempDirectory("kdbx-types").toFile()
        try {
            val editable = EditingFixtures.open("kdbx4-aes-argon2d")
            val id = EditingFixtures.document("kdbx4-aes-argon2d").entry("Second renamed").uuid
            EntryEditing.apply(EntryChanges(fieldTypes = mapOf("Gültig bis" to "until")), assertNotNull(EntryEditing.entry(id, editable.root)), editable.root, now)
            val file = File(folder, "typed.kdbx").apply { writeBytes(EditingFixtures.write(editable)) }

            val exported = KeePassXcCli.run("export", "-f", "xml", file.path)

            assertEquals(0, exported.code, exported.err)
            val entry = KdbxXmlReader.read(exported.out).entries.single { it.uuid == id }
            // KeePassXC's export adds its own `_LAST_MODIFIED`.
            assertEquals("""{"Gültig bis":"until"}""", entry.customData[key])
        } finally {
            folder.deleteRecursively()
        }
    }
}
