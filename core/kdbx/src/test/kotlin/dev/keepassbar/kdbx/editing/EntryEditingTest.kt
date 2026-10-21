package dev.keepassbar.kdbx.editing

import dev.keepassbar.domain.secret.Secret
import dev.keepassbar.domain.vault.CustomField
import dev.keepassbar.domain.vault.EntryChanges
import dev.keepassbar.domain.vault.FieldValue
import dev.keepassbar.kdbx.KeePassXcCli
import dev.keepassbar.kdbx.editing.EditingFixtures.open
import dev.keepassbar.kdbx.editing.EditingFixtures.reread
import dev.keepassbar.kdbx.tree.XElement
import dev.keepassbar.kdbx.xml.KdbxField
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Editing an entry in the tree (SPEC §3.3; the Mac `KDBXWriteTests` "entry edit" expectations). */
class EntryEditingTest {
    private val folder: File = Files.createTempDirectory("kdbx-edit").toFile()
    private val now = Instant.ofEpochSecond(1_900_000_000)

    @AfterTest
    fun deleteFolder() {
        folder.deleteRecursively()
    }

    private fun EditableDatabase.element(id: String): XElement = assertNotNull(EntryEditing.entry(id, root))

    @Test
    fun fieldsCustomFieldsTagsAndExpiryChangeAndTheOldVersionGoesIntoTheHistory() {
        val before = EditingFixtures.document("kdbx4-aes-aeskdf")
        val one = before.entry("Entry <one> ü")
        val editable = open("kdbx4-aes-aeskdf")
        val element = editable.element(one.uuid)
        val historyBefore = element.first("History")?.all("Entry")?.size ?: 0
        val expiry = Instant.ofEpochSecond(2_000_000_000)

        EntryEditing.apply(
            EntryChanges(
                title = "Renamed & <new>",
                password = Secret.of("n3w\r\npass"),
                notes = "",
                customFields = listOf(
                    CustomField.protected("API key 2", "rotated"), // renamed: keeps its place
                    CustomField.plain("Plain", "visible"),
                    CustomField.protected("PIN", "1234"), // new; "Empty secret" goes
                ),
                tags = listOf("work", " x "),
                expiry = EntryChanges.Expiry(expires = true, time = expiry),
            ),
            element, editable.root, now,
        )
        val after = reread(editable)

        val edited = after.entries.single { it.uuid == one.uuid }
        assertEquals(plain("Renamed & <new>"), edited.field("Title"))
        assertEquals(protected("n3w\r\npass"), edited.field("Password"))
        assertEquals(plain(""), edited.field("Notes"))
        assertEquals(one.field("UserName"), edited.field("UserName"), "untouched")
        assertEquals(
            listOf(
                KdbxField("API key 2", protected("rotated")),
                KdbxField("Plain", plain("visible")),
                KdbxField("PIN", protected("1234")),
            ),
            edited.custom(),
        )
        assertEquals(one.field("otp"), edited.field("otp"), "the one-time code is left alone")
        assertEquals("work;x", edited.tags)
        assertTrue(edited.expires)
        assertEquals(expiry, edited.expiryTime)
        assertEquals(now, edited.lastModified)
        assertEquals(one.attachments, edited.attachments)
        assertEquals(before.entries.filter { it.uuid != one.uuid }, after.entries.filter { it.uuid != one.uuid }, "other entries")

        val history = assertNotNull(element.first("History")).all("Entry")
        assertEquals(historyBefore + 1, history.size)
        val snapshot = history.last()
        fun XElement.string(key: String) = all("String").first { it.first("Key")?.text == key }.first("Value")!!
        assertEquals("Entry <one> ü", snapshot.string("Title").text)
        assertNull(snapshot.first("History"), "a history entry has no history of its own")
        assertEquals(Secret.of("p&ss<w>rd \"ü\" 🔑"), snapshot.string("Password").secret, "still protected in the history")
    }

    @Test
    fun customFieldsInANewOrderAreWrittenInItWithValuesAndProtectionKept() {
        val one = EditingFixtures.document("kdbx4-aes-aeskdf").entry("Entry <one> ü")
        val editable = open("kdbx4-aes-aeskdf")
        val reversed = one.custom().reversed()
        assertTrue(reversed.size >= 2, "the fixture has fields to reorder")

        EntryEditing.apply(
            EntryChanges(customFields = reversed.map { CustomField(it.key, it.value) }),
            editable.element(one.uuid), editable.root, now,
        )

        val edited = reread(editable).entries.single { it.uuid == one.uuid }
        assertEquals(reversed, edited.custom())
        assertEquals(one.field("otp"), edited.field("otp"))
        assertEquals(one.field("Password"), edited.field("Password"))
    }

    @Test
    fun theHistoryIsTrimmedToHistoryMaxItems() {
        val editable = open("kdbx4-aes-aeskdf")
        val meta = editable.root.first("Meta")!!
        meta.first("HistoryMaxItems")?.let(meta::remove)
        meta.append(XElement("HistoryMaxItems", "2"))
        val id = EditingFixtures.document("kdbx4-aes-aeskdf").entry("Second renamed").uuid
        val element = editable.element(id)

        for (i in 0 until 4) EntryEditing.apply(EntryChanges(notes = "v$i"), element, editable.root, now)

        val history = element.first("History")!!.all("Entry")
        assertEquals(2, history.size)
        assertEquals("v2", history.last().all("String").first { it.first("Key")?.text == "Notes" }.first("Value")?.text)
        assertNull(EntryEditing.entry("nope", editable.root))
    }

    @Test
    fun nothingChangesWithoutChanges() {
        val editable = open("kdbx4-aes-aeskdf")
        val one = EditingFixtures.document("kdbx4-aes-aeskdf").entry("Entry <one> ü")
        val element = editable.element(one.uuid)
        val history = element.first("History")?.all("Entry")?.size

        EntryEditing.apply(EntryChanges(), element, editable.root, now)

        assertEquals(history, element.first("History")?.all("Entry")?.size)
        assertEquals(one, reread(editable).entries.single { it.uuid == one.uuid })
    }

    @Test
    fun standardFieldsAreProtectedAsMemoryProtectionSays() {
        val editable = open("kdbx4-aes-aeskdf")
        val protection = editable.root.first("Meta")!!.first("MemoryProtection")!!
        protection.first("ProtectUserName")!!.text = "True"
        protection.first("ProtectPassword")!!.text = "False"
        val id = EditingFixtures.document("kdbx4-aes-aeskdf").entry("Second renamed").uuid

        EntryEditing.apply(EntryChanges(username = "me", password = Secret.of("open")), editable.element(id), editable.root, now)

        val edited = reread(editable).entries.single { it.uuid == id }
        assertEquals(protected("me"), edited.field("UserName"))
        assertEquals(plain("open"), edited.field("Password"))
    }

    @Test
    fun theTreeKeepsItsOwnCopyOfANewSecret() {
        val editable = open("kdbx4-aes-aeskdf")
        val id = EditingFixtures.document("kdbx4-aes-aeskdf").entry("Second renamed").uuid
        val password = Secret.of("mine")
        val field = Secret.of("pin")

        EntryEditing.apply(
            EntryChanges(password = password, customFields = listOf(CustomField("PIN", FieldValue.Protected(field)))),
            editable.element(id), editable.root, now,
        )
        password.wipe()
        field.wipe()

        val edited = reread(editable).entries.single { it.uuid == id }
        assertEquals(protected("mine"), edited.field("Password"))
        assertEquals(protected("pin"), edited.field("PIN"))
    }

    @Test
    fun keepassxcCliReadsAnEditedEntryProtectedFieldsIncluded() {
        if (!KeePassXcCli.isInstalled) return
        val editable = open("kdbx4-aes-argon2d")
        val id = EditingFixtures.document("kdbx4-aes-argon2d").entry("Second renamed").uuid
        EntryEditing.apply(
            EntryChanges(
                title = "Edited",
                password = Secret.of("pässwörd 2"),
                customFields = listOf(CustomField.protected("PIN", "4321")),
            ),
            editable.element(id), editable.root, now,
        )
        val file = File(folder, "edited.kdbx").apply { writeBytes(EditingFixtures.write(editable)) }

        val shown = KeePassXcCli.run("show", "-s", "-a", "Password", "-a", "PIN", file.path, "Web & Mail/Edited")

        assertEquals(0, shown.code, shown.err)
        assertEquals("pässwörd 2\n4321\n", shown.text)
    }
}
