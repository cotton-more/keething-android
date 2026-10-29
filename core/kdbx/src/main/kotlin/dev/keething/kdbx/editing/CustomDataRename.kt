package dev.keething.kdbx.editing

import dev.keething.kdbx.tree.XElement

/** What [CustomDataRename.rename] changed. */
public data class CustomDataRenamed(
    /** Entries outside the history whose item was renamed or dropped. */
    val entries: Int,
    val historyItems: Int,
    /** Items dropped because the entry already had one under the new key (it is newer and wins). */
    val dropped: Int,
)

/**
 * Renames an item of the entries' `CustomData` everywhere: every entry, its history items, the recycle bin. The
 * value and the item's own times stay; nothing goes into the history and no entry's times change: the
 * entries hold what they held, under another name.
 */
internal object CustomDataRename {
    fun rename(from: String, to: String, root: XElement): CustomDataRenamed {
        var entries = 0
        var history = 0
        var dropped = 0
        fun visit(element: XElement, inHistory: Boolean) {
            if (element.name == "Entry" && renameIn(element, from, to) { dropped++ }) {
                if (inHistory) history++ else entries++
            }
            for (child in element.elements) visit(child, inHistory || element.name == "History")
        }
        visit(root, inHistory = false)
        return CustomDataRenamed(entries, history, dropped)
    }

    private fun renameIn(entry: XElement, from: String, to: String, onDropped: () -> Unit): Boolean {
        val data = entry.first("CustomData") ?: return false
        val items = data.all("Item")
        val old = items.firstOrNull { it.first("Key")?.text == from } ?: return false
        if (items.any { it.first("Key")?.text == to }) {
            data.remove(old)
            onDropped()
        } else {
            old.first("Key")!!.text = to
        }
        return true
    }
}
