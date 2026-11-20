package dev.keething.kdbx.editing

import dev.keething.domain.secret.Secret
import dev.keething.domain.totp.TotpConfig
import dev.keething.domain.totp.TotpFormat
import dev.keething.domain.vault.CustomField
import dev.keething.domain.vault.EntryChanges
import dev.keething.domain.vault.FieldValue
import dev.keething.domain.vault.TotpChange
import dev.keething.kdbx.KeePassXcCli
import dev.keething.kdbx.editing.EditingFixtures.open
import dev.keething.kdbx.editing.EditingFixtures.reread
import dev.keething.kdbx.schema.KdbxSchema
import dev.keething.kdbx.tree.XElement
import dev.keething.kdbx.xml.KdbxField
import dev.keething.kdbx.xml.KdbxTime
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.Base64
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
        assertEquals(KdbxTime.base64(now), element.first("Times")!!.first("LastAccessTime")!!.text)

        // An entry without Tags given tags (an empty one left out): its Tags before Times. Its times in ISO 8601 stay so.
        val second = editable.element(EditingFixtures.document("kdbx4-aes-aeskdf").entry("Second renamed").uuid)
        second.first("Tags")?.let(second::remove)
        second.first("Times")!!.elements.filter { it.name.endsWith("Time") }.forEach { it.text = KdbxTime.iso(Instant.ofEpochSecond(1_600_000_000)) }
        EntryEditing.apply(EntryChanges(tags = listOf("", "home")), second, editable.root, now)
        assertEquals("home", second.first("Tags")?.text)
        assertTrue(second.elements.indexOf(second.first("Tags")) < second.elements.indexOf(second.first("Times")))
        assertEquals(emptyList<Any>(), KdbxSchema.violations(editable.root).toList())
        val times = second.first("Times")!!
        assertEquals(KdbxTime.iso(now), times.first("LastModificationTime")!!.text)
        assertEquals(KdbxTime.iso(now), times.first("LastAccessTime")!!.text)
    }

    @Test
    fun aOneTimeCodeSetIsOneProtectedOtpLinkTheOldFieldsGoAndRemovedNoneIsLeft() {
        val one = EditingFixtures.document("kdbx4-aes-aeskdf").entry("Entry <one> ü")
        assertNotNull(one.field("otp"), "the fixture has a code")
        val editable = open("kdbx4-aes-aeskdf")
        val element = editable.element(one.uuid)
        val uri = "otpauth://totp/Entry:me?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&period=30&digits=8&issuer=Entry"
        val legacy = XElement("String").apply {
            append(XElement("Key", "TOTP Seed"))
            append(XElement("Value", "GEZDGNBV"))
        }
        element.insertAfter(legacy, element.first("Times"))
        for (key in listOf("TimeOtp-Secret", "TimeOtp-Secret-Hex", "TimeOtp-Secret-Base64")) {
            element.insertAfter(XElement("String").apply { append(XElement("Key", key)); append(XElement("Value", "00")) }, legacy)
        }
        fun totpKeys() = element.all("String").mapNotNull { it.first("Key")?.text }.filter { it in EntryChanges.TOTP_FIELD_NAMES }

        EntryEditing.apply(EntryChanges(totp = TotpChange.Set(config(), uri)), element, editable.root, now)

        assertEquals(listOf("otp"), totpKeys())
        val edited = reread(editable).entries.single { it.uuid == one.uuid }
        assertEquals(protected(uri), edited.field("otp"))
        assertEquals(one.custom(), edited.custom(), "custom fields untouched")

        EntryEditing.apply(EntryChanges(totp = TotpChange.Remove), element, editable.root, now)
        assertEquals(emptyList(), totpKeys())
    }

    @Test
    fun aNewEntryGetsItsOtpAfterTheOtherFields() {
        val editable = open("kdbx4-aes-aeskdf")
        val entry = XElement("Entry").apply { append(XElement("UUID", "AAAAAAAAAAAAAAAAAAAAAA==")); append(XElement("Times")) }
        val uri = "otpauth://totp/A:none?secret=GEZDGNBV&period=30&digits=6&issuer=A"

        EntryEditing.write(EntryChanges(title = "A", customFields = listOf(CustomField.plain("X", "1")), totp = TotpChange.Set(config(), uri)), entry, editable.root, now)

        assertEquals(listOf("Title", "X", "otp"), entry.all("String").map { it.first("Key")?.text })
        assertEquals(Secret.of(uri), entry.all("String").last().first("Value")?.secret)
    }

    private fun config() = TotpConfig(Secret.of("12345678901234567890"), digits = 8)

    /** An entry's code fields as `key=value`, a protected one with `*`. */
    private fun codeFields(entry: XElement): List<String> = entry.all("String").mapNotNull { string ->
        val key = string.first("Key")?.text ?: ""
        val value = string.first("Value")
        if (key !in EntryChanges.TOTP_FIELD_NAMES || value == null) null else value.secret?.let { "$key=${it.reveal()}*" } ?: "$key=${value.text}"
    }

    /** The entry's code fields become [fields], after its other fields. */
    private fun put(fields: List<Pair<String, String>>, entry: XElement) {
        entry.all("String").filter { it.first("Key")?.text in EntryChanges.TOTP_FIELD_NAMES }.forEach(entry::remove)
        var previous = entry.all("String").lastOrNull() ?: entry.first("Times")
        for ((key, value) in fields) {
            val string = XElement("String").apply { append(XElement("Key", key)); append(XElement("Value", value)) }
            entry.insertAfter(string, previous)
            previous = string
        }
    }

    @Test
    fun aCodeIsWrittenInTheEntrysFormatElseTheDatabasesMostUsedElseAsOtp() {
        val editable = open("kdbx4-aes-aeskdf")
        val root = editable.root
        val group = assertNotNull(root.first("Root")?.first("Group"))
        var made = 0
        fun entry(): XElement = XElement("Entry").apply {
            append(XElement("UUID", Base64.getEncoder().encodeToString(ByteArray(16) { (++made).toByte() })))
            append(XElement("Times"))
        }
        fun newEntry(): XElement = entry().also { group.insertBefore(it, group.first("Group")) }
        fun setCode(entry: XElement, config: TotpConfig = TotpConfig(Secret.of("12345678901234567890"), digits = 7)) =
            EntryEditing.apply(EntryChanges(totp = TotpChange.Set(config, "LINK")), entry, root, now)
        val base32 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
        fun everyEntry(group: XElement): List<XElement> =
            group.all("Entry") + group.all("Group").flatMap(::everyEntry)
        everyEntry(assertNotNull(root.first("Root"))).forEach { put(emptyList(), it) }
        assertEquals(emptyMap(), EntryEditing.totpFormats(root))

        val plain = newEntry()
        setCode(plain)
        assertEquals(listOf("otp=LINK*"), codeFields(plain), "none in the database: otp")

        put(listOf("TimeOtp-Secret-Hex" to "00", "TimeOtp-Length" to "8"), newEntry())
        assertEquals(mapOf(TotpFormat.LINK to 1, TotpFormat.KEEPASS to 1), EntryEditing.totpFormats(root))
        val tie = newEntry()
        setCode(tie)
        assertEquals(listOf("otp=LINK*"), codeFields(tie), "a tie is otp")

        put(listOf("TimeOtp-Secret-Base32" to base32), newEntry())
        val history = newEntry()
        history.append(XElement("History").apply { append(entry().also { put(listOf("TOTP Seed" to base32), it) }) })
        assertEquals(mapOf(TotpFormat.LINK to 2, TotpFormat.KEEPASS to 2), EntryEditing.totpFormats(root), "histories do not count")
        put(listOf("TimeOtp-Secret" to "x"), newEntry())
        val most = newEntry()
        setCode(most)
        assertEquals(
            listOf("TimeOtp-Secret-Base32=$base32*", "TimeOtp-Length=7", "TimeOtp-Period=30", "TimeOtp-Algorithm=HMAC-SHA-1"),
            codeFields(most),
            "the format most of the database's codes use",
        )

        val seeded = newEntry()
        put(listOf("Note" to "kept", "TOTP Seed" to "GEZDGNBV", "TOTP Settings" to "30;6", "Later" to "kept"), seeded)
        assertEquals(TotpFormat.SEED, EntryEditing.totpFormat(seeded))
        setCode(seeded)
        assertEquals(listOf("Note", "TOTP Seed", "TOTP Settings", "Later"), seeded.all("String").map { it.first("Key")?.text }, "where the code was")
        assertEquals(listOf("TOTP Seed=$base32*", "TOTP Settings=30;7"), codeFields(seeded), "the entry's own format")
        setCode(seeded, TotpConfig(Secret.of("12345678901234567890"), algorithm = TotpConfig.Algorithm.SHA256))
        assertEquals(listOf("otp=LINK*"), codeFields(seeded), "TOTP Settings has no algorithm: a link")

        val mixed = newEntry()
        put(listOf("otp" to "LINK", "TimeOtp-Secret-Base32" to base32, "TimeOtp-Length" to "8"), mixed)
        setCode(mixed)
        assertEquals(listOf("otp=LINK*"), codeFields(mixed), "KeePassXC's otp beside KeePass's fields: the otp's format, one format")
        put(listOf("TimeOtp-Secret" to "x", "TimeOtp-Period" to "30"), mixed)
        setCode(mixed, TotpConfig(Secret.of("12345678901234567890"), digits = 10))
        assertEquals(listOf("otp=LINK*"), codeFields(mixed), "more digits than KeePass makes: a link")
        put(listOf("TimeOtp-Secret" to "x", "TimeOtp-Period" to "30"), mixed)
        setCode(mixed)
        assertEquals(
            listOf("TimeOtp-Secret-Base32=$base32*", "TimeOtp-Length=7", "TimeOtp-Period=30", "TimeOtp-Algorithm=HMAC-SHA-1"),
            codeFields(mixed),
        )
        val before = codeFields(mixed)
        EntryEditing.apply(EntryChanges(title = "Renamed"), mixed, root, now)
        assertEquals(before, codeFields(mixed), "a save that does not change the code leaves its fields")
        EntryEditing.apply(EntryChanges(totp = TotpChange.Remove), mixed, root, now)
        assertEquals(emptyList(), codeFields(mixed))

        val reread = reread(editable).entries.single { it.uuid == EntryLifecycle.uuidText(most) }
        assertEquals(protected(base32), reread.field("TimeOtp-Secret-Base32"), "protected in the file")
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

        // Ten when the file does not say; none at all with -1.
        meta.first("HistoryMaxItems")?.let(meta::remove)
        for (i in 0 until 12) EntryEditing.apply(EntryChanges(notes = "w$i"), element, editable.root, now)
        assertEquals(10, element.first("History")!!.all("Entry").size)
        meta.append(XElement("HistoryMaxItems", "-1"))
        for (i in 0 until 3) EntryEditing.apply(EntryChanges(notes = "x$i"), element, editable.root, now)
        assertEquals(13, element.first("History")!!.all("Entry").size)
    }

    @Test
    fun aSnapshotOfAnEntryWrittenOutOfOrderIsInTheSchemasOrder() {
        // Strongbox writes an entry's Times after its fields (checks/android.md, 2026-11-05).
        val editable = open("kdbx4-aes-aeskdf")
        val id = EditingFixtures.document("kdbx4-aes-aeskdf").entry("Second renamed").uuid
        val element = editable.element(id)
        val times = element.first("Times")!!
        element.remove(times)
        element.insertBefore(times, element.first("AutoType") ?: element.first("History"))
        val before = KdbxSchema.violations(editable.root)

        EntryEditing.apply(EntryChanges(notes = "edited"), element, editable.root, now)

        val snapshot = element.first("History")!!.all("Entry").last()
        val names = snapshot.elements.map { it.name }
        assertTrue(names.indexOf("Times") < names.indexOf("String"), "$names")
        assertEquals(before, KdbxSchema.violations(editable.root), "no violation the file did not have")
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
        assertEquals(protected("open"), edited.field("Password"), "protected as it was, whatever Meta says now (L7)")
    }

    // spec: kdbx.round-trip/AC6
    @Test
    fun aStandardFieldKeepsItsOwnProtectionThroughAnEdit() {
        // The review's L7: a value protected by its own flag while Meta/MemoryProtection leaves that field unprotected
        // (KeePassXC keeps a field's own; KeePass 2.x lets the database's be switched off later).
        val editable = open("kdbx4-aes-aeskdf")
        val protection = editable.root.first("Meta")!!.first("MemoryProtection")!!
        for (tag in listOf("ProtectTitle", "ProtectUserName", "ProtectPassword", "ProtectURL")) protection.first(tag)!!.text = "False"
        val id = EditingFixtures.document("kdbx4-aes-aeskdf").entry("Second renamed").uuid
        val element = editable.element(id)
        val url = element.all("String").single { it.first("Key")?.text == "URL" }.first("Value")!!
        url.secret = Secret.of(url.text)

        EntryEditing.apply(EntryChanges(title = "Title", url = "https://new.example", password = Secret.of("still hidden")), element, editable.root, now)

        val edited = reread(editable).entries.single { it.uuid == id }
        assertEquals(protected("https://new.example"), edited.field("URL"), "its own protection kept")
        assertEquals(protected("still hidden"), edited.field("Password"), "never written in the clear")
        assertEquals(plain("Title"), edited.field("Title"), "plain as it was")
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
