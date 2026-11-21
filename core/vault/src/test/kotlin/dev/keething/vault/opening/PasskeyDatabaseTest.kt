package dev.keething.vault.opening

import dev.keething.domain.secret.Secret
import dev.keething.domain.vault.CustomField
import dev.keething.domain.vault.EntryChanges
import dev.keething.domain.vault.Passkey
import dev.keething.domain.vault.Vault
import dev.keething.domain.vault.VaultEdit
import dev.keething.kdbx.editing.EntryStrings
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.vault.saving.SaveResult
import dev.keething.vault.saving.VaultSaver
import dev.keething.vault.session.FakeStore
import dev.keething.vault.session.fixtureBytes
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A passkey KeePassXC 2.7.12 put into an entry (keething-spec card.passkey; `fixtures/interop/README.md`): a fictitious
 * one for webauthn.io, user alice.example, imported from a `.passkey` file into a copy of `keepassxc-2.7.12.kdbx`.
 */
class PasskeyDatabaseTest {
    private val name = "interop/keepassxc-2.7.12-passkey"
    private val title = "webauthn.io (Passkey)"
    private val cli = File("/Applications/KeePassXC.app/Contents/MacOS/keepassxc-cli")

    private fun key() = DatabaseKey.of(Secret.of("pw"))

    private fun open(store: FakeStore): Vault = assertIs<OpenResult.Opened>(VaultOpener().open(store, key())).opened.vault

    private fun passkeyStrings(file: ByteArray, uuid: String) = EntryStrings.of(file, key(), uuid, Passkey::isField)

    // spec: card.passkey/AC1
    @Test
    fun keePassXcsPasskeyIsReadAsOneApartFromTheCustomFields() {
        val entry = open(FakeStore(fixtureBytes(name))).entries.single { it.title == title }

        assertEquals(Passkey("webauthn.io", "alice.example"), entry.passkey)
        assertTrue("Passkey" in entry.tags)
        assertTrue(entry.customFields.none { Passkey.isField(it.name) })
        assertEquals(
            listOf(
                "KPEX_PASSKEY_USERNAME", "KPEX_PASSKEY_CREDENTIAL_ID", "KPEX_PASSKEY_PRIVATE_KEY_PEM", "KPEX_PASSKEY_RELYING_PARTY",
                "KPEX_PASSKEY_USER_HANDLE", "KPEX_PASSKEY_FLAG_BE", "KPEX_PASSKEY_FLAG_BS",
            ).sorted(),
            entry.passkeyFields.map { it.name }.sorted(),
        )
        assertEquals(
            setOf("KPEX_PASSKEY_CREDENTIAL_ID", "KPEX_PASSKEY_PRIVATE_KEY_PEM", "KPEX_PASSKEY_USER_HANDLE"),
            entry.passkeyFields.filter { it.isProtected }.map { it.name }.toSet(),
            "protected as KeePassXC wrote them",
        )
    }

    // spec: card.passkey/AC6
    @Test
    fun aSaveFromTheEditorKeepsEveryPasskeyFieldAsTheFileHadIt() {
        val file = fixtureBytes(name)
        val store = FakeStore(file)
        val entry = open(store).entries.single { it.title == title }
        val before = passkeyStrings(file, entry.id)
        assertEquals(7, before.size)

        val changes = EntryChanges(username = "bob", customFields = entry.customFields + CustomField.plain("Added", "on the phone"))
        val saved = VaultSaver(store) { _, _, _ -> }.save(VaultEdit.Update(entry.id, entry.lastModified, changes), key(), Instant.now())

        val after = assertIs<SaveResult.Saved>(saved).opened.vault.entries.single { it.id == entry.id }
        assertEquals("bob", after.username)
        assertEquals(listOf(CustomField.plain("Added", "on the phone")), after.customFields)
        assertEquals(before, passkeyStrings(store.content!!, entry.id))
        assertEquals(entry.passkey, after.passkey)
    }

    // spec: card.passkey/AC8
    @Test
    fun aRemovedPasskeyGoesWithEveryOneOfItsFieldsAndNothingElse() {
        val file = fixtureBytes(name)
        val store = FakeStore(file)
        val entry = open(store).entries.single { it.title == title }
        val others = EntryStrings.of(file, key(), entry.id) { !Passkey.isField(it) }

        val saved = VaultSaver(store) { _, _, _ -> }
            .save(VaultEdit.Update(entry.id, entry.lastModified, EntryChanges(removePasskey = true)), key(), Instant.now())

        val after = assertIs<SaveResult.Saved>(saved).opened.vault.entries.single { it.id == entry.id }
        assertNull(after.passkey)
        assertEquals(emptyList(), after.passkeyFields)
        assertEquals(emptyList(), EntryStrings.of(store.content!!, key(), entry.id, Passkey::isField))
        assertEquals(others, EntryStrings.of(store.content!!, key(), entry.id) { !Passkey.isField(it) }, "the other fields as they were")
        assertTrue("Passkey" in after.tags)
        assertTrue(entry.passkeyFields.filter { it.isProtected }.isNotEmpty())
    }

    // spec: card.passkey/AC6
    @Test
    fun keePassXcReadsThePasskeyThePhoneSavedBack() {
        if (!cli.canExecute()) return
        val folder = Files.createTempDirectory("passkey").toFile()
        try {
            val file = fixtureBytes(name)
            val store = FakeStore(file)
            val entry = open(store).entries.single { it.title == title }
            VaultSaver(store) { _, _, _ -> }
                .save(VaultEdit.Update(entry.id, entry.lastModified, EntryChanges(notes = "Edited on the phone.")), key(), Instant.now())
            val saved = File(folder, "saved.kdbx").apply { writeBytes(store.content!!) }

            fun show(file: File): String {
                val process = ProcessBuilder(cli.path, "show", "-q", "-s", "-a", "KPEX_PASSKEY_PRIVATE_KEY_PEM", file.path, title).start()
                process.outputStream.use { it.write("pw\n".encodeToByteArray()) }
                val out = process.inputStream.use { it.readBytes() }.decodeToString()
                assertTrue(process.waitFor(60, TimeUnit.SECONDS))
                assertEquals(0, process.exitValue())
                return out
            }
            val original = show(File(folder, "original.kdbx").apply { writeBytes(file) })
            assertTrue(original.startsWith("-----BEGIN PRIVATE KEY-----"))
            assertEquals(original, show(saved))
        } finally {
            folder.deleteRecursively()
        }
    }
}
