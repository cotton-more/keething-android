package dev.keething.kdbx.schema

import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.EntryChanges
import dev.keething.domain.vault.VaultEdit
import dev.keething.kdbx.Fixtures
import dev.keething.kdbx.editing.EditException
import dev.keething.kdbx.editing.EditableDatabase
import dev.keething.kdbx.editing.EditingFixtures
import dev.keething.kdbx.editing.EditingFixtures.key
import dev.keething.kdbx.editing.EntryEditing
import dev.keething.kdbx.editing.EntryLifecycle
import dev.keething.kdbx.reader.KdbxReader
import dev.keething.kdbx.tree.XElement
import dev.keething.kdbx.tree.XText
import dev.keething.kdbx.tree.XmlTree
import dev.keething.kdbx.writer.WrittenDocuments
import dev.keething.kdbx.xml.KdbxTime
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SPEC §3.3 (keething-spec kdbx.keepass-schema; the Mac `SchemaTests` expectations): what the app writes follows
 * KeePass's own XML schema, `KDBX_XML.xsd`. Every file any test writes is validated against it ([SchemaCheck]);
 * these are the rules around it. Fixture databases (password `pw`, KDBX 4.0, written by keepassxc-cli).
 */
class KdbxSchemaTest {
    private val now = Instant.ofEpochSecond(1_900_000_000)
    private val uuid = "AAECAwQFBgcICQoLDA0ODw=="
    private val databases = listOf("kdbx4-aes-argon2d", "kdbx4-aes-aeskdf", "kdbx4-chacha20-argon2id")

    private fun open(bytes: ByteArray): EditableDatabase = KdbxReader.openForEditing(bytes, key)

    private fun top(root: XElement): XElement = root.first("Root")!!.first("Group")!!

    /** The entries outside histories, in document order. */
    private fun entries(root: XElement): List<XElement> {
        fun walk(group: XElement): List<XElement> = group.all("Entry") + group.all("Group").flatMap(::walk)
        return walk(top(root))
    }

    private fun validate(root: XElement) = SchemaCheck.validate(XmlTree.withoutSecrets(root), KdbxSchema.violations(root))

    private fun names(element: XElement) = element.elements.map { it.name }

    private fun icon(vararg extra: XElement) = XElement("Icon").apply {
        append(XElement("UUID", uuid))
        append(XElement("Data", "iVBORw0KGgo="))
        extra.forEach(::append)
    }

    private fun customIcons(root: XElement): XElement {
        val meta = root.first("Meta")!!
        return meta.first("CustomIcons") ?: XElement("CustomIcons").also(meta::append)
    }

    private fun history(entry: XElement): XElement = entry.first("History") ?: XElement("History").also(entry::append)

    /** A copy of [entry] as a version for its history (without a history of its own). */
    private fun version(entry: XElement): XElement = entry.copy().also { copy -> copy.first("History")?.let(copy::remove) }

    // The schema and the checker

    @Test
    fun theSchemaAsPublishedCompilesWithItsColourPatternPatchedAndKeePassXcsFilesAreValidByBothChecks() {
        assertNotNull(WrittenDocuments.observer, "SchemaCheck watches every written file (JUnit's extension auto-detection)")
        for (name in databases) {
            val editable = EditingFixtures.open(name)
            val (xsd, own) = validate(editable.root)
            assertTrue(xsd.isEmpty(), "$name: ${xsd.take(3)}")
            assertTrue(own.isEmpty(), "$name: ${own.take(3)}")
            editable.wipe()
        }
        val export = XmlTree.parse(Fixtures.bytes("kdbx4-export.xml"))
        assertEquals(emptyList(), validate(export).first)
        assertEquals(emptyList(), validate(export).second)
    }

    private class Change(val name: String, val valid: Boolean, val problem: String?, val xsdValid: Boolean = valid, val change: (XElement) -> Unit)

    @Test
    fun theCheckerFindsWhatTheSchemaFindsAndAllowsWhatItAllows() {
        fun entry(root: XElement) = entries(root).first()
        fun times(e: XElement) = e.first("Times")!!
        val changes = listOf(
            Change("nothing", true, null) {},
            Change("an entry's PreviousParentGroup after Times", false, "PreviousParentGroup: out of order") { root ->
                val e = entry(root)
                e.insertAfter(XElement("PreviousParentGroup", uuid), times(e))
            },
            Change("an entry's PreviousParentGroup before Times", true, null) { root ->
                val e = entry(root)
                e.insertBefore(XElement("PreviousParentGroup", uuid), times(e))
            },
            Change("a group's PreviousParentGroup after its entries", false, "PreviousParentGroup: out of order") { root ->
                top(root).append(XElement("PreviousParentGroup", uuid))
            },
            Change("a group's Tags and PreviousParentGroup in place", true, null) { root ->
                val g = top(root)
                val previous = XElement("PreviousParentGroup", uuid)
                g.insertAfter(previous, g.first("LastTopVisibleEntry"))
                g.insertAfter(XElement("Tags", "a;b"), previous)
            },
            Change("Expires false", false, "Times/Expires: not True or False") { root -> times(entry(root)).first("Expires")!!.text = "false" },
            Change("EnableSearching false", true, null) { root -> top(root).first("EnableSearching")!!.text = "false" },
            Change("EnableSearching maybe", false, "EnableSearching: not True, False or Null") { root ->
                top(root).first("EnableSearching")!!.text = "maybe"
            },
            Change("a 15-byte UUID", false, "UUID: not a 16-byte base64 UUID") { root -> entry(root).first("UUID")!!.text = "AAECAwQFBgcICQoLDA0O" },
            Change("a UUID with blanks around", true, null) { root ->
                val u = entry(root).first("UUID")!!
                u.text = " ${u.text}\n"
            },
            Change("an empty LastTopVisibleEntry", false, "LastTopVisibleEntry: not a 16-byte base64 UUID") { root ->
                top(root).first("LastTopVisibleEntry")!!.text = ""
            },
            Change("a lower-case colour", false, "ForegroundColor: not #RRGGBB (upper case) or empty") { root ->
                EntryEditing.child("ForegroundColor", entry(root)).text = "#ff0000"
            },
            Change("an upper-case colour", true, null) { root -> EntryEditing.child("ForegroundColor", entry(root)).text = "#FF00A0" },
            Change("a time that is no time", false, "Times/LastModificationTime: not a KeePass time") { root ->
                times(entry(root)).first("LastModificationTime")!!.text = "yesterday"
            },
            Change("an ISO time (KDBX 3)", true, null) { root ->
                times(entry(root)).first("LastModificationTime")!!.text = "2026-10-28T18:50:00Z"
            },
            Change("February 30th", false, "Times/LastModificationTime: not a KeePass time") { root ->
                times(entry(root)).first("LastModificationTime")!!.text = "2026-02-30T18:50:00Z"
            },
            Change("IconID -1", false, "IconID: not a number of 0 or more") { root -> entry(root).first("IconID")!!.text = "-1" },
            Change("IconID +7", true, null) { root -> entry(root).first("IconID")!!.text = "+7" },
            // Where libxml2 (2.9) is stricter than XML Schema and the JDK's validator: as strict as libxml2, as on the Mac.
            Change("IconID 7 with blanks", false, "IconID: not a number of 0 or more", xsdValid = true) { root ->
                entry(root).first("IconID")!!.text = " 7 "
            },
            Change("UsageCount +1 (unsigned: digits only)", false, "Times/UsageCount: not a number of 0 or more", xsdValid = true) { root ->
                times(entry(root)).first("UsageCount")!!.text = "+1"
            },
            Change("a blank between the padding's two =", false, "UUID: not a 16-byte base64 UUID", xsdValid = true) { root ->
                val u = entry(root).first("UUID")!!
                u.text = u.text.trim().removeSuffix("==") + "= ="
            },
            Change("HistoryMaxItems -2", false, "HistoryMaxItems: not a number of -1 or more") { root ->
                root.first("Meta")!!.first("HistoryMaxItems")!!.text = "-2"
            },
            Change("a plugin's element in an entry", false, "Plugin: not expected here") { root -> entry(root).append(XElement("Plugin", "x")) },
            Change("an entry's Times twice", false, "Times: more than once") { root ->
                val e = entry(root)
                e.insertAfter(XElement("Times"), times(e))
            },
            Change("Times' elements in any order", true, null) { root ->
                val t = times(entry(root))
                val first = t.elements.first()
                t.remove(first)
                t.append(first)
            },
            Change("a field without its value", false, "String/Value: missing") { root ->
                val s = entry(root).first("String")!!
                s.remove(s.first("Value")!!)
            },
            Change("an attribute on a UUID", false, "UUID: attribute Protected not allowed") { root ->
                entry(root).first("UUID")!!.setAttribute("Protected", "True")
            },
            Change("Protected=\"true\" on a value", false, "String/Value: attribute Protected not True or False") { root ->
                val value = entries(root).flatMap { it.all("String") }.map { it.first("Value")!! }.first { it.secret == null }
                value.setAttribute("Protected", "true")
            },
            Change("text in Times", false, "Times: text not allowed") { root -> times(entry(root)).children.add(XText("x")) },
            // The JDK's validator misses the `.//Group/Entry` part of KUuid's selector (libxml2 finds it): as the schema says.
            Change("two entries with one UUID", false, "used more than once", xsdValid = true) { root ->
                top(root).insertBefore(entry(root).copy(), top(root).first("Group"))
            },
            Change("two groups with one UUID", false, "used more than once") { root ->
                val sub = top(root).first("Group")!!
                sub.first("UUID")!!.text = top(root).first("UUID")!!.text
            },
            Change("a version in the history with its entry's UUID", true, null) { root ->
                val e = entry(root)
                history(e).append(version(e))
            },
            Change("a custom icon that is not there", false, "CustomIconUUID: no such custom icon") { root ->
                EntryEditing.child("CustomIconUUID", entry(root)).text = uuid
            },
            Change("a custom icon that is there", true, null) { root ->
                customIcons(root).append(icon())
                EntryEditing.child("CustomIconUUID", entry(root)).text = uuid
            },
            Change("a deleted object without its time", false, "DeletedObjects/DeletedObject/DeletionTime: missing") { root ->
                val deleted = root.first("Root")!!.first("DeletedObjects") ?: XElement("DeletedObjects").also(root.first("Root")!!::append)
                deleted.append(XElement("DeletedObject").apply { append(XElement("UUID", uuid)) })
            },
            Change("Meta's elements in any order", true, null) { root ->
                val meta = root.first("Meta")!!
                val first = meta.elements.first()
                meta.remove(first)
                meta.append(first)
            },
            Change("a custom data item's time in an entry (only Meta's have one)", false, "CustomData/Item/LastModificationTime: not expected here") { root ->
                val e = entry(root)
                val data = e.first("CustomData") ?: XElement("CustomData").also { e.insertBefore(it, e.first("History")) }
                data.append(
                    XElement("Item").apply {
                        append(XElement("Key", "k"))
                        append(XElement("Value", "v"))
                        append(XElement("LastModificationTime", KdbxTime.base64(now)))
                    },
                )
            },
            Change("an auto-type obfuscation of 2", false, "AutoType/DataTransferObfuscation: not 0 or 1") { root ->
                entry(root).first("AutoType")!!.first("DataTransferObfuscation")!!.text = "2"
            },
            Change("an attachment's Ref that is no number", false, "Binary/Value: attribute Ref not a number of 0 or more") { root ->
                val e = entry(root)
                val binary = XElement("Binary").apply {
                    append(XElement("Key", "a"))
                    append(XElement("Value").apply { setAttribute("Ref", "x") })
                }
                e.insertBefore(binary, e.first("AutoType"))
            },
            Change("an old Keething version in the history", false, "PreviousParentGroup: out of order") { root ->
                val e = entry(root)
                val version = version(e)
                version.insertAfter(XElement("PreviousParentGroup", uuid), version.first("Times"))
                history(e).append(version)
            },
            Change("Meta missing", false, "Meta: missing") { root -> root.remove(root.first("Meta")!!) },
        )
        for (change in changes) {
            val editable = EditingFixtures.open("kdbx4-aes-argon2d")
            change.change(editable.root)
            val (xsd, own) = validate(editable.root)
            assertEquals(change.xsdValid, xsd.isEmpty(), "${change.name}: KDBX_XML.xsd says ${xsd.take(2)}")
            assertEquals(change.valid, own.isEmpty(), "${change.name}: KdbxSchema says ${own.take(2)}")
            change.problem?.let { problem -> assertTrue(own.any { it.problem == problem }, "${change.name}: ${own.map { it.problem }}") }
            editable.wipe()
        }
    }

    @Test
    fun aVersionInAnEntrysHistoryCountsAsTheEntrySoCopyingOneIntoTheHistoryAddsNothing() {
        val editable = EditingFixtures.open("kdbx4-aes-argon2d")
        val root = editable.root
        val e = entries(root).first()
        e.append(XElement("Plugin", "x"))
        val before = KdbxSchema.violations(root)
        assertEquals(listOf("entry ${EntryLifecycle.uuidText(e)}"), before.map { it.place })

        EntryEditing.apply(EntryChanges(title = "Edited"), e, root, now)

        assertEquals(emptyList(), KdbxSchema.added(before, KdbxSchema.violations(root)))
    }

    // PreviousParentGroup (AC1, AC2)

    @Test
    fun anEntrysPreviousParentGroupGoesAfterTagsBeforeTimesAndAfterWhicheverOfTheEarlierOnesItHas() {
        val e = entries(EditingFixtures.open("kdbx4-aes-argon2d").root).first()
        for (name in listOf("Tags", "QualityCheck", "OverrideURL", "BackgroundColor", "ForegroundColor", "CustomIconUUID", "IconID")) {
            e.first(name)?.let(e::remove)
            e.first("PreviousParentGroup")?.let(e::remove)
            KdbxSchema.insert(XElement("PreviousParentGroup", EntryLifecycle.NULL_UUID), e)
            assertEquals(names(e).indexOf("Times"), names(e).indexOf("PreviousParentGroup") + 1, "without $name")
        }
        assertEquals(listOf("UUID", "PreviousParentGroup", "Times"), names(e).take(3))
    }

    @Test
    fun anOldKeethingFilesPreviousParentGroupAfterTimesIsMovedToItsPlaceByAnySaveInHistoriesToo() {
        // As Keething wrote it before: an entry in the bin with PreviousParentGroup after Times, and a version of it.
        SchemaCheck.allowViolations()
        val old = EditingFixtures.open("kdbx4-chacha20-argon2id")
        val all = entries(old.root)
        val recycledId = EntryLifecycle.uuidText(all.first())
        val otherId = EntryLifecycle.uuidText(all.last())
        EntryLifecycle.moveToRecycleBin(recycledId, old.root, now)
        val recycled = EntryLifecycle.locate(recycledId, old.root)!!.entry
        val previous = recycled.first("PreviousParentGroup")!!
        recycled.remove(previous)
        recycled.insertAfter(previous, recycled.first("Times"))
        history(recycled).append(version(recycled))
        assertEquals(setOf("PreviousParentGroup: out of order"), KdbxSchema.violations(old.root).map { it.problem }.toSet())
        val file = EditingFixtures.write(old)

        // A save that does not touch that entry.
        val editable = open(file)
        editable.apply(VaultEdit.Update(otherId, null, EntryChanges(title = "Other")), now)
        val saved = open(editable.write(key).file)

        assertEquals(emptySet(), KdbxSchema.violations(saved.root))
        assertEquals(emptyList(), validate(saved.root).first)
        val moved = EntryLifecycle.locate(recycledId, saved.root)!!.entry
        // The entry and the version added above (the fixture's older versions never were in the bin).
        for (element in listOf(moved, moved.first("History")!!.all("Entry").last())) {
            assertEquals(names(element).indexOf("Times"), names(element).indexOf("PreviousParentGroup") + 1)
            assertEquals(previous.text, element.first("PreviousParentGroup")?.text)
        }
    }

    @Test
    fun tagsGivenToAnEntryInTheBinGoBeforeItsPreviousParentGroup() {
        val editable = EditingFixtures.open("kdbx4-aes-argon2d")
        val root = editable.root
        val entry = entries(root).first()
        entry.first("Tags")?.let(entry::remove)
        EntryLifecycle.moveToRecycleBin(EntryLifecycle.uuidText(entry), root, now)

        EntryEditing.apply(EntryChanges(tags = listOf("Work")), entry, root, now)

        val names = names(entry)
        assertEquals(listOf("Tags", "PreviousParentGroup", "Times"), names.drop(names.indexOf("Tags")).take(3))
        assertEquals(emptySet(), KdbxSchema.violations(root))
    }

    @Test
    fun aMissingTimesIsMadeWhereTheSchemaHasIt() {
        val editable = EditingFixtures.open("kdbx4-aes-argon2d")
        val entry = entries(editable.root).first()
        entry.remove(entry.first("Times")!!)
        KdbxSchema.insert(XElement("PreviousParentGroup", uuid), entry)

        EntryEditing.apply(EntryChanges(title = "Edited"), entry, editable.root, now)

        assertEquals(names(entry).indexOf("PreviousParentGroup") + 1, names(entry).indexOf("Times"))
        assertEquals(emptySet(), KdbxSchema.violations(editable.root))
    }

    // The save's check (AC4)

    @Test
    fun aDatabaseThatAlreadyHadViolationsStillSavesAndKeepsThem() {
        SchemaCheck.allowViolations()
        val old = EditingFixtures.open("kdbx4-aes-argon2d")
        val all = entries(old.root)
        val plugin = all.first()
        plugin.insertBefore(XElement("PluginData", "kept"), plugin.first("History"))
        val pluginId = EntryLifecycle.uuidText(plugin)
        var file = EditingFixtures.write(old)

        for ((id, title) in listOf(EntryLifecycle.uuidText(all.last()) to "Other", pluginId to "Itself")) {
            val editable = open(file)
            editable.apply(VaultEdit.Update(id, null, EntryChanges(title = title)), now)
            file = editable.write(key).file
        }

        val saved = open(file)
        assertEquals("kept", EntryLifecycle.locate(pluginId, saved.root)!!.entry.first("PluginData")?.text)
        assertEquals(listOf("PluginData: not expected here"), KdbxSchema.violations(saved.root).map { it.problem }, "the version in its history counts as the entry")
    }

    @Test
    fun aSaveThatWouldAddAViolationIsRefused() {
        // The group's custom icon is not in the file: a new entry would take it, and so refer to an icon that is not there.
        SchemaCheck.allowViolations()
        val old = EditingFixtures.open("kdbx4-aes-argon2d")
        val group = top(old.root)
        group.insertAfter(XElement("CustomIconUUID", uuid), group.first("IconID"))
        val editable = open(EditingFixtures.write(old))

        editable.apply(VaultEdit.Create(Entry.newId(), EntryLifecycle.uuidText(top(editable.root)), EntryChanges(title = "New")), now)

        val refused = assertFailsWith<EditException.BreaksFormat> { editable.write(key) }
        assertEquals("an entry's CustomIconUUID: no such custom icon", refused.what)
        assertEquals("a group's Tags: more than once (and 2 more)", SchemaViolation("group x", "Tags: more than once").describe(more = 2))
    }

    // The version (AC5)

    @Test
    fun kdbx41OnlyForWhatOnly41HasOtherwiseTheVersionReadIsKept() {
        val changes: List<Pair<String, (XElement) -> Unit>> = listOf(
            "nothing" to {},
            "an entry's PreviousParentGroup" to { root -> KdbxSchema.insert(XElement("PreviousParentGroup", uuid), entries(root).first()) },
            "a null PreviousParentGroup" to { root -> KdbxSchema.insert(XElement("PreviousParentGroup", EntryLifecycle.NULL_UUID), entries(root).first()) },
            "a group's PreviousParentGroup" to { root -> KdbxSchema.insert(XElement("PreviousParentGroup", uuid), top(root)) },
            "an entry's QualityCheck" to { root -> KdbxSchema.insert(XElement("QualityCheck", "False"), entries(root).first()) },
            "a group's tags" to { root -> KdbxSchema.insert(XElement("Tags", "Work"), top(root)) },
            "a group's empty Tags" to { root -> KdbxSchema.insert(XElement("Tags"), top(root)) },
            "an entry's tags" to { root -> EntryEditing.child("Tags", entries(root).first()).text = "Work" },
            "a custom icon's name" to { root -> customIcons(root).append(icon(XElement("Name", "Bank"))) },
            "a custom icon's time" to { root -> customIcons(root).append(icon(XElement("LastModificationTime", KdbxTime.base64(now)))) },
            "a custom data item's time" to { root ->
                root.first("Meta")!!.first("CustomData")!!.first("Item")!!.append(XElement("LastModificationTime", KdbxTime.base64(now)))
            },
        )
        val raises = setOf(
            "an entry's PreviousParentGroup", "a group's PreviousParentGroup", "an entry's QualityCheck", "a group's tags",
            "a custom icon's name", "a custom icon's time", "a custom data item's time",
        )
        for ((name, change) in changes) {
            val editable = EditingFixtures.open("kdbx4-aes-argon2d")
            change(editable.root)
            assertEquals(if (name in raises) "4.1" else "4.0", KdbxReader.format(EditingFixtures.write(editable)).version, name)
        }
        // Never lowered: a 4.1 file stays 4.1 without any of them.
        val editable = EditingFixtures.open("kdbx4-aes-argon2d")
        KdbxSchema.insert(XElement("PreviousParentGroup", uuid), entries(editable.root).first())
        val reopened = open(EditingFixtures.write(editable))
        assertNotNull(entries(reopened.root).first().first("PreviousParentGroup")).text = EntryLifecycle.NULL_UUID
        assertEquals("4.1", KdbxReader.format(EditingFixtures.write(reopened)).version, "the version read is kept")
    }
}
