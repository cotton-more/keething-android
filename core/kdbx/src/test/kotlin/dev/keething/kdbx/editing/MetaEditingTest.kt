package dev.keething.kdbx.editing

import dev.keething.domain.vault.VaultEdit
import dev.keething.kdbx.editing.EditingFixtures.key
import dev.keething.kdbx.editing.EditingFixtures.open
import dev.keething.kdbx.reader.KdbxReader
import dev.keething.kdbx.schema.KdbxSchema
import dev.keething.kdbx.tree.XElement
import dev.keething.kdbx.xml.KdbxTime
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The database's name and colour written into `Meta` (keething-spec databases.rename-recolour). */
class MetaEditingTest {
    private val now = Instant.ofEpochSecond(1_900_000_000)

    private fun meta(file: ByteArray): XElement {
        val editable = KdbxReader.openForEditing(file, key)
        return editable.root.first("Meta")!!.copy().also { editable.wipe() }
    }

    // spec: databases.rename-recolour/AC1
    @Test
    fun aNameAndAColourAreWrittenWithTheirTimesAndNothingElseInMetaChanges() {
        val editable = open("kdbx4-aes-argon2d")
        val before = editable.root.first("Meta")!!.copy()
        val rootName = editable.root.first("Root")!!.first("Group")!!.first("Name")!!.text

        editable.apply(VaultEdit.NameAndColor(name = "  Family  ", color = "#2E9E4F"), now)
        val after = meta(editable.write(key).file)

        assertEquals("Family", after.first("DatabaseName")!!.text)
        assertEquals("#2E9E4F", after.first("Color")!!.text)
        assertEquals(now, KdbxTime.parse(after.first("DatabaseNameChanged")!!.text))
        assertEquals(now, KdbxTime.parse(after.first("SettingsChanged")!!.text))
        val changed = setOf("DatabaseName", "DatabaseNameChanged", "Color", "SettingsChanged", "HeaderHash")
        val untouched = { meta: XElement -> meta.elements.filter { it.name !in changed }.map { it.name to it.text } }
        assertEquals(untouched(before), untouched(after))
        val reread = KdbxReader.openForEditing(editable.write(key).file, key)
        assertEquals(rootName, reread.root.first("Root")!!.first("Group")!!.first("Name")!!.text, "the root group keeps its name")
        reread.wipe()
    }

    // spec: databases.rename-recolour/AC4
    @Test
    fun onlyWhatIsAskedIsWritten() {
        val editable = open("kdbx4-aes-argon2d")
        val before = editable.root.first("Meta")!!.copy()

        editable.apply(VaultEdit.NameAndColor(color = "#D93A3A"), now)
        val after = meta(editable.write(key).file)

        assertEquals("#D93A3A", after.first("Color")!!.text)
        assertEquals(before.first("DatabaseName")?.text, after.first("DatabaseName")?.text)
        assertEquals(before.first("DatabaseNameChanged")?.text, after.first("DatabaseNameChanged")?.text)
    }

    // spec: databases.rename-recolour/AC2
    @Test
    fun anEmptyNameAndNoColourEmptyThem() {
        val editable = open("kdbx4-aes-argon2d")
        editable.apply(VaultEdit.NameAndColor(name = "Named", color = "#0E9AA7"), now)
        val named = KdbxReader.openForEditing(editable.write(key).file, key)

        named.apply(VaultEdit.NameAndColor(name = "   ", color = ""), now)
        val document = KdbxReader.open(named.write(key).file, key).document

        assertEquals("", document.meta.databaseName)
        assertEquals("", document.meta.color)
    }

    @Test
    fun elementsTheFileLacksGoWhereTheSchemaHasThem() {
        val editable = open("kdbx4-aes-argon2d")
        val meta = editable.root.first("Meta")!!
        for (name in listOf("DatabaseName", "DatabaseNameChanged", "Color", "SettingsChanged")) meta.first(name)?.let(meta::remove)
        val violations = editable.schemaViolations()

        editable.apply(VaultEdit.NameAndColor(name = "Placed", color = "#8E5BD9"), now)

        assertEquals(violations, editable.schemaViolations())
        // Each between the elements the schema puts around it (the file's own order further on is KeePassXC's).
        val order = KdbxSchema.model(KdbxSchema.Complex.META).index
        val elements = meta.elements
        for (name in listOf("DatabaseName", "DatabaseNameChanged", "Color", "SettingsChanged")) {
            val at = elements.indexOfFirst { it.name == name }
            assertTrue(at >= 0, "$name written")
            val own = order.getValue(name)
            elements.getOrNull(at - 1)?.let { assertTrue(order.getValue(it.name) < own, "$name after ${it.name}") }
            elements.getOrNull(at + 1)?.let { assertTrue(order.getValue(it.name) > own, "$name before ${it.name}") }
        }
        assertEquals("Placed", KdbxReader.open(editable.write(key).file, key).document.meta.databaseName)
    }
}
