package dev.keething.kdbx.editing

import dev.keething.kdbx.tree.XElement
import dev.keething.kdbx.tree.XmlTree
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class CustomDataRenameTest {
    private fun item(key: String, value: String) = "<Item><Key>$key</Key><Value>$value</Value><LastModificationTime>2024-01-01T00:00:00Z</LastModificationTime></Item>"

    private fun entry(id: String, items: String, history: String = "") =
        "<Entry><UUID>$id</UUID><Times><LastModificationTime>2024-02-02T00:00:00Z</LastModificationTime></Times>" +
            "<String><Key>Title</Key><Value>$id</Value></String><CustomData>$items</CustomData><History>$history</History></Entry>"

    private fun tree(groups: String): XElement =
        XmlTree.parse("<KeePassFile><Meta><CustomData>${item("old", "meta")}</CustomData></Meta><Root>$groups</Root></KeePassFile>".encodeToByteArray())

    private fun XElement.text(): String {
        val out = ByteArrayOutputStream()
        XmlTree.write(this, out, protect = null)
        return out.toString(Charsets.UTF_8)
    }

    @Test
    fun everyEntryAndHistoryItemIsRenamedAndNothingElseChanges() {
        val root = tree(
            "<Group><UUID>R</UUID><Name>P</Name>" +
                entry("a", item("other", "x") + item("old", "{\"A\":\"iban\"}"), history = entry("a", item("old", "{}"))) +
                entry("b", item("other", "y")) +
                "<Group><UUID>BIN</UUID><Name>Recycle Bin</Name>${entry("c", item("old", "{}"))}</Group></Group>",
        )
        val expected = root.text().replace("<Key>old</Key><Value>{", "<Key>new</Key><Value>{")

        val renamed = CustomDataRename.rename("old", "new", root)

        assertEquals(CustomDataRenamed(entries = 2, historyItems = 1, dropped = 0), renamed)
        assertEquals(expected, root.text(), "the values, the items' times, the entries' times and the database's own item as they were")
    }

    @Test
    fun anEntryWithBothKeysKeepsTheNewOne() {
        val root = tree("<Group><UUID>R</UUID><Name>P</Name>${entry("a", item("old", "{\"A\":\"x\"}") + item("new", "{\"A\":\"y\"}"))}</Group>")

        val renamed = CustomDataRename.rename("old", "new", root)

        assertEquals(CustomDataRenamed(entries = 1, historyItems = 0, dropped = 1), renamed)
        val items = root.first("Root")!!.first("Group")!!.first("Entry")!!.first("CustomData")!!.all("Item")
        assertEquals(listOf("new" to "{\"A\":\"y\"}"), items.map { it.first("Key")!!.text to it.first("Value")!!.text })
    }

    @Test
    fun nothingToRenameChangesNothing() {
        val root = tree("<Group><UUID>R</UUID><Name>P</Name>${entry("a", item("other", "x"))}</Group>")
        val before = root.text()

        assertEquals(CustomDataRenamed(0, 0, 0), CustomDataRename.rename("old", "new", root))
        assertEquals(before, root.text())
    }
}
