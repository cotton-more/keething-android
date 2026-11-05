package dev.keething.vault.opening

import dev.keething.domain.secret.Secret
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.EntryChanges
import dev.keething.domain.vault.Vault
import dev.keething.domain.vault.VaultEdit
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.kdbx.reader.KdbxReader
import dev.keething.kdbx.schema.SchemaCheck
import dev.keething.vault.saving.SaveFailure
import dev.keething.vault.saving.SaveResult
import dev.keething.vault.saving.VaultSaver
import dev.keething.vault.session.FakeStore
import dev.keething.vault.session.fixtureBytes
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.w3c.dom.Element

/**
 * Databases other KeePass apps made (keething-spec plans/android-release-1.0.md T1; `fixtures/interop/README.md`),
 * each with the same content: groups Work › Projects, an entry "Main" with tags, an expiry, a protected custom
 * field, an attachment, a custom icon, a one-time code and a history, and an entry in the recycle bin. Strongbox's
 * file breaks KeePass's schema as Strongbox writes it (a group's `Name` before its `UUID`, an entry's `Times` after its
 * fields), so the files written back may too.
 */
class OtherAppsDatabasesTest {
    private val kdbx4 = listOf("interop/keepassxc-2.7.12", "interop/strongbox-1.65.2")
    private val keepassdx = "interop/keepassdx-4.5.8"

    private fun key() = DatabaseKey.of(Secret.of("pw"))

    @BeforeTest
    fun strongboxsOwnSchemaViolations() = SchemaCheck.allowViolations()

    private fun open(store: FakeStore): Vault = assertIs<OpenResult.Opened>(VaultOpener().open(store, key())).opened.vault

    private fun Vault.titled(title: String): Entry = (entries + recycleBin).single { it.title == title }

    @Test
    fun eachAppsDatabaseReadsAsItWasMade() {
        for (name in kdbx4 + keepassdx) {
            val store = FakeStore(fixtureBytes(name))
            val vault = open(store)

            val main = vault.titled("Main")
            // KeePassDX adds a tag of its own, "OTP", to an entry with a one-time code.
            assertTrue(main.tags.containsAll(listOf("alpha", "beta")), name)
            assertTrue(main.expires, name)
            val pin = main.customFields.single { it.name == "PIN" }
            assertTrue(pin.isProtected, name)
            assertEquals(listOf("note.txt"), main.attachments.map { it.name }, name)
            assertTrue(main.customIconId in vault.customIcons, name)
            val code = assertNotNull(main.totp, name)
            assertTrue(code.secret.useBytes { it.contentEquals(JBSWY3DPEHPK3PXP) }, name)
            val versions = assertIs<HistoryResult.Read>(VaultOpener().history(store, key(), main.id)).versions
            assertTrue(versions.history.isNotEmpty(), name)

            assertEquals(listOf("Work"), vault.titled("Work mail").groupPath, name)
            assertEquals(listOf("Work", "Projects"), vault.titled("Project server").groupPath, name)
            assertTrue(vault.recycleBin.any { it.title == "Deleted" }, name)
        }
    }

    @Test
    fun anEditSavedOnThePhoneChangesNothingButThatEntry() {
        for (name in kdbx4) {
            val store = FakeStore(fixtureBytes(name))
            val before = open(store)
            val main = before.titled("Main")

            val result = VaultSaver(store) { _, _, _ -> }
                .save(VaultEdit.Update(main.id, main.lastModified, EntryChanges(notes = main.notes + "\nEdited on the phone.")), key(), Instant.now())

            val after = assertIs<SaveResult.Saved>(result, name).opened.vault
            assertEquals(main.notes + "\nEdited on the phone.", after.titled("Main").notes, name)
            assertEquals(main.copy(notes = "", lastModified = null), after.titled("Main").copy(notes = "", lastModified = null), name)
            // Against the file written back without a change: a save also moves a PreviousParentGroup out of the
            // schema's order (KeePassXC's in the bin) to its place (SPEC §3.3).
            val (was, now) = xmlWithout(writtenBack(fixtureBytes(name)), main.id) to xmlWithout(store.content!!, main.id)
            assertEquals(was.lines() - now.lines().toSet(), now.lines() - was.lines().toSet(), "$name: only the entry edited changed")
            val versions = assertIs<HistoryResult.Read>(VaultOpener().history(store, key(), main.id)).versions
            val versionsBefore = assertIs<HistoryResult.Read>(VaultOpener().history(FakeStore(fixtureBytes(name)), key(), main.id)).versions
            assertEquals(versionsBefore.history.size + 1, versions.history.size, "$name: the version before the edit in the history")
        }
    }

    @Test
    fun keepassxcOpensWhatThePhoneSavedIntoEachAppsDatabase() {
        if (!CLI.canExecute()) return
        val folder = Files.createTempDirectory("interop").toFile()
        try {
            for (name in kdbx4) {
                val store = FakeStore(fixtureBytes(name))
                val main = open(store).titled("Main")
                VaultSaver(store) { _, _, _ -> }
                    .save(VaultEdit.Update(main.id, main.lastModified, EntryChanges(notes = "Edited on the phone.")), key(), Instant.now())
                val saved = File(folder, "saved.kdbx").apply { writeBytes(store.content!!) }

                val process = ProcessBuilder(CLI.path, "show", "-q", "-s", "-a", "PIN", saved.path, "Main").start()
                process.outputStream.use { it.write("pw\n".encodeToByteArray()) }
                val out = process.inputStream.use { it.readBytes() }.decodeToString()
                assertTrue(process.waitFor(60, TimeUnit.SECONDS))

                assertEquals(0, process.exitValue(), name)
                assertEquals("1234\n", out, name)
            }
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun keepassdxsKdbx31DatabaseIsViewedOnlyAndASaveWritesNothing() {
        // KeePassDX 4.5.8 writes a new database as KDBX 3.1 (AES-KDF).
        val file = fixtureBytes(keepassdx)
        val store = FakeStore(file)
        val opened = assertIs<OpenResult.Opened>(VaultOpener().open(store, key())).opened
        assertTrue(opened.viewOnly)
        assertTrue(!assertIs<OpenResult.Opened>(VaultOpener().open(FakeStore(fixtureBytes(kdbx4.first())), key())).opened.viewOnly)
        val main = opened.vault.titled("Main")

        val result = VaultSaver(store) { _, _, _ -> }
            .save(VaultEdit.Update(main.id, main.lastModified, EntryChanges(notes = "Edited on the phone.")), key(), Instant.now())

        assertEquals(SaveFailure.ViewOnly, assertIs<SaveResult.Failed>(result).failure)
        assertTrue(store.content.contentEquals(file), "the file as it was")
    }

    // spec: kdbx.kdbx3/AC7
    // spec: kdbx.kdbx3/AC8
    @Test
    fun keepassdxsDatabaseIsUpgradedToKdbx40ItsKdbx31FileKeptBesideIt() {
        val file = fixtureBytes(keepassdx)
        val store = FakeStore(file)
        val before = open(store)
        val backedUp = mutableListOf<ByteArray>()

        val result = VaultSaver(store) { bytes, _, _ -> backedUp += bytes.copyOf() }.upgrade(key(), Instant.now())

        val opened = assertIs<SaveResult.Saved>(result).opened
        assertEquals(listOf("test (KDBX 3.1).kdbx"), store.kept.keys.toList())
        assertTrue(store.kept.values.single().contentEquals(file), "the 3.1 file as it was")
        assertEquals(1, backedUp.size, "backed up as before any save")
        val upgraded = store.content!!
        assertEquals("4.0", KdbxReader.format(upgraded).version)
        assertEquals(KdbxReader.format(file).kdf, KdbxReader.format(upgraded).kdf)
        assertTrue(!opened.viewOnly)
        assertEquals(before.titled("Main").customFields, opened.vault.titled("Main").customFields)
        assertEquals(before.titled("Main").attachments.map { it.name }, opened.vault.titled("Main").attachments.map { it.name })

        // From then on a KDBX 4 database like any other.
        val main = opened.vault.titled("Main")
        val edited = VaultSaver(store) { _, _, _ -> }
            .save(VaultEdit.Update(main.id, main.lastModified, EntryChanges(notes = "Edited on the phone.")), key(), Instant.now())
        assertEquals("Edited on the phone.", assertIs<SaveResult.Saved>(edited).opened.vault.titled("Main").notes)
    }

    // spec: kdbx.kdbx3/AC7
    @Test
    fun aKdbx31FileThatCannotBeKeptIsNotUpgraded() {
        val file = fixtureBytes(keepassdx)
        val store = FakeStore(file).apply { failKeep = true }

        val result = VaultSaver(store) { _, _, _ -> error("no backup without the copy") }.upgrade(key(), Instant.now())

        assertIs<SaveFailure.CopyNotKept>(assertIs<SaveResult.Failed>(result).failure)
        assertTrue(store.content.contentEquals(file), "the file as it was")
        assertEquals(0, store.replaced)
    }

    @Test
    fun aDatabaseAlreadyKdbx4IsNotUpgradedAgain() {
        val store = FakeStore(fixtureBytes(kdbx4.first()))

        val result = VaultSaver(store) { _, _, _ -> }.upgrade(key(), Instant.now())

        assertEquals(SaveFailure.ChangedElsewhere, assertIs<SaveResult.Failed>(result).failure)
        assertTrue(store.kept.isEmpty())
    }

    // spec: kdbx.kdbx3/AC8
    @Test
    fun keepassxcOpensKeepassdxsUpgradedDatabase() {
        if (!CLI.canExecute()) return
        val folder = Files.createTempDirectory("interop").toFile()
        try {
            val store = FakeStore(fixtureBytes(keepassdx))
            assertIs<SaveResult.Saved>(VaultSaver(store) { _, _, _ -> }.upgrade(key(), Instant.now()))
            val upgraded = File(folder, "upgraded.kdbx").apply { writeBytes(store.content!!) }

            val process = ProcessBuilder(CLI.path, "show", "-q", "-s", "-a", "PIN", upgraded.path, "Main").start()
            process.outputStream.use { it.write("pw\n".encodeToByteArray()) }
            val out = process.inputStream.use { it.readBytes() }.decodeToString()
            assertTrue(process.waitFor(60, TimeUnit.SECONDS))

            assertEquals(0, process.exitValue())
            assertEquals("1234\n", out)
        } finally {
            folder.deleteRecursively()
        }
    }

    private fun writtenBack(bytes: ByteArray): ByteArray {
        val editable = KdbxReader.openForEditing(bytes, key())
        return try {
            editable.write(key()).file
        } finally {
            editable.wipe()
        }
    }

    /** The file's XML (secrets left out) without the entry [id]: the rest of the file, to compare. */
    private fun xmlWithout(bytes: ByteArray, id: String): String {
        val editable = KdbxReader.openForEditing(bytes, key())
        val xml = try {
            editable.xmlWithoutSecrets()
        } finally {
            editable.wipe()
        }
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.inputStream())
        val entries = document.getElementsByTagName("Entry")
        val edited = (0 until entries.length).map { entries.item(it) as Element }
            .single { it.parentNode.nodeName == "Group" && it.getElementsByTagName("UUID").item(0).textContent == id }
        edited.parentNode.removeChild(edited)
        return java.io.StringWriter().also {
            javax.xml.transform.TransformerFactory.newInstance().newTransformer()
                .transform(javax.xml.transform.dom.DOMSource(document), javax.xml.transform.stream.StreamResult(it))
        }.toString()
    }

    private companion object {
        val CLI = File("/Applications/KeePassXC.app/Contents/MacOS/keepassxc-cli")

        /** The fixtures' one-time code secret, Base32 `JBSWY3DPEHPK3PXP`. */
        val JBSWY3DPEHPK3PXP = byteArrayOf(0x48, 0x65, 0x6C, 0x6C, 0x6F, 0x21, 0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
    }
}
