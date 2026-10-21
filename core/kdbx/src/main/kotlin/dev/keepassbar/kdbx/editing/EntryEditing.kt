package dev.keepassbar.kdbx.editing

import dev.keepassbar.domain.secret.Secret
import dev.keepassbar.domain.vault.CustomField
import dev.keepassbar.domain.vault.EntryChanges
import dev.keepassbar.domain.vault.FieldValue
import dev.keepassbar.kdbx.tree.XElement
import dev.keepassbar.kdbx.xml.KdbxTime
import java.time.Instant

/**
 * Applies an [EntryChanges] to an entry of the tree as KeePass does (SPEC §3.3; port of the Mac `EntryEditing`):
 * the entry as it was goes into its history first (trimmed to `Meta/HistoryMaxItems`), then the fields change,
 * and `LastModificationTime` / `LastAccessTime` become now. Nothing else in the entry or the document is touched.
 * A protected value goes into the tree as a copy of its secret: the tree owns and wipes its own.
 */
internal object EntryEditing {
    /** The current version of an entry (not one in a history), anywhere under `Root`. */
    fun entry(uuid: String, root: XElement): XElement? {
        fun search(group: XElement): XElement? {
            for (child in group.elements) {
                if (child.name == "Entry" && child.first("UUID")?.text?.trim() == uuid) return child
                if (child.name == "Group") search(child)?.let { return it }
            }
            return null
        }
        return root.first("Root")?.let(::search)
    }

    fun apply(changes: EntryChanges, entry: XElement, root: XElement, now: Instant) {
        if (changes.isEmpty) return
        snapshotIntoHistory(entry, historyMaxItems(root))
        write(changes, entry, root, now)
    }

    /** The fields and times of [changes], without a history snapshot (a new entry has no old version). */
    fun write(changes: EntryChanges, entry: XElement, root: XElement, now: Instant) {
        val protection = memoryProtection(root)
        fun standard(key: String, text: String?) {
            if (text == null) return
            setString(key, if (protection.getValue(key)) FieldValue.Protected(Secret.of(text)) else FieldValue.Plain(text), entry)
        }
        standard("Title", changes.title)
        standard("UserName", changes.username)
        changes.password?.let { password ->
            val value = if (protection.getValue("Password")) FieldValue.Protected(password.copy()) else FieldValue.Plain(password.reveal())
            setString("Password", value, entry)
        }
        standard("URL", changes.url)
        standard("Notes", changes.notes)
        changes.customFields?.let { setCustomFields(it, entry) }
        changes.tags?.let { setTags(it, entry) }

        val times = child("Times", entry, before = listOf("String", "Binary", "AutoType", "History"))
        val iso = times.elements.any { KdbxTime.isIso(it.text) }
        setTime("LastModificationTime", now, times, iso)
        setTime("LastAccessTime", now, times, iso)
        changes.expiry?.let { expiry ->
            setText("Expires", if (expiry.expires) "True" else "False", times)
            expiry.time?.let { setTime("ExpiryTime", it, times, iso) }
        }
    }

    // History

    private fun snapshotIntoHistory(entry: XElement, maxItems: Int) {
        val snapshot = entry.copy()
        snapshot.first("History")?.let { inner ->
            inner.wipeSecrets()
            snapshot.remove(inner)
        }
        val history = entry.first("History") ?: XElement("History").also(entry::append)
        history.append(snapshot)
        if (maxItems >= 0) {
            val items = history.all("Entry")
            for (old in items.take(maxOf(0, items.size - maxItems))) {
                old.wipeSecrets()
                history.remove(old)
            }
        }
        if (history.elements.isEmpty()) history.children.clear()
    }

    /** `Meta/HistoryMaxItems`; -1 means unlimited. KeePass's default is 10. */
    private fun historyMaxItems(root: XElement): Int =
        root.first("Meta")?.first("HistoryMaxItems")?.text?.trim()?.toIntOrNull() ?: 10

    /** `Meta/MemoryProtection`: which standard fields are protected (the password by default). */
    fun memoryProtection(root: XElement): Map<String, Boolean> {
        val meta = root.first("Meta")?.first("MemoryProtection")
        val result = mutableMapOf("Title" to false, "UserName" to false, "Password" to true, "URL" to false, "Notes" to false)
        for (key in EntryChanges.STANDARD_KEYS) {
            val tag = if (key == "UserName") "ProtectUserName" else "Protect$key"
            meta?.first(tag)?.text?.let { result[key] = it.trim().equals("true", ignoreCase = true) }
        }
        return result
    }

    // Strings

    private fun strings(entry: XElement): List<Pair<XElement, String>> =
        entry.all("String").map { it to (it.first("Key")?.text ?: "") }

    /** [value] becomes the field's: a secret is taken over by the tree. */
    private fun setString(key: String, value: FieldValue, entry: XElement) {
        val existing = strings(entry).firstOrNull { it.second == key }?.first
        if (existing != null) {
            setValue(existing.first("Value") ?: XElement("Value").also(existing::append), value)
            return
        }
        val element = XElement("String")
        element.append(XElement("Key", key))
        element.append(XElement("Value").also { setValue(it, value) })
        entry.insertAfter(element, strings(entry).lastOrNull()?.first ?: entry.first("Times"))
    }

    private fun setValue(element: XElement, value: FieldValue) {
        when (value) {
            is FieldValue.Protected -> element.secret = value.secret
            is FieldValue.Plain -> element.text = value.text
        }
    }

    /**
     * The custom fields become exactly [fields], in that order (a renamed field keeps its place); an unchanged
     * field keeps its element. The one-time code's fields stay where they are.
     */
    private fun setCustomFields(fields: List<CustomField>, entry: XElement) {
        val reserved = EntryChanges.RESERVED_FIELD_NAMES
        val existing = strings(entry).filter { it.second !in reserved }
        val byKey = LinkedHashMap<String, XElement>()
        existing.forEach { (element, key) -> byKey.putIfAbsent(key, element) }
        val anchor = strings(entry).lastOrNull { it.second in EntryChanges.STANDARD_KEYS }?.first
        existing.forEach { (element, _) -> entry.remove(element) }
        // Fields dropped by the edit go with their secrets.
        existing.filter { (element, key) -> fields.none { it.name == key } || byKey[key] !== element }
            .forEach { (element, _) -> element.wipeSecrets() }
        var previous = anchor ?: strings(entry).lastOrNull()?.first ?: entry.first("Times")
        for (field in fields) {
            if (field.name in reserved) continue
            val element = byKey.remove(field.name) ?: XElement("String").apply {
                append(XElement("Key", field.name))
                append(XElement("Value"))
            }
            val value = element.first("Value") ?: XElement("Value").also(element::append)
            setValue(value, copyOf(field.value))
            entry.insertAfter(element, previous)
            previous = element
        }
    }

    private fun copyOf(value: FieldValue): FieldValue =
        if (value is FieldValue.Protected) FieldValue.Protected(value.secret.copy()) else value

    private fun setTags(tags: List<String>, entry: XElement) {
        val text = tags.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(";")
        val existing = entry.first("Tags")
        if (existing != null) {
            existing.text = text
        } else if (text.isNotEmpty()) {
            val element = XElement("Tags", text)
            val elements = entry.elements
            val index = elements.indexOfFirst { it.name == "Times" }
            if (index > 0) entry.insertAfter(element, elements[index - 1]) else entry.insertAfter(element, entry.first("UUID"))
        }
    }

    // Times

    /** The child [name] of [parent], made before the first of [before] when missing. */
    fun child(name: String, parent: XElement, before: List<String>): XElement {
        parent.first(name)?.let { return it }
        val element = XElement(name)
        val elements = parent.elements
        val index = elements.indexOfFirst { it.name in before }
        if (index > 0) parent.insertAfter(element, elements[index - 1]) else parent.append(element)
        return element
    }

    fun setTime(name: String, time: Instant, times: XElement, iso: Boolean) {
        setText(name, if (iso) KdbxTime.iso(time) else KdbxTime.base64(time), times)
    }

    fun setText(name: String, text: String, parent: XElement) {
        val existing = parent.first(name)
        if (existing != null) existing.text = text else parent.append(XElement(name, text))
    }
}
