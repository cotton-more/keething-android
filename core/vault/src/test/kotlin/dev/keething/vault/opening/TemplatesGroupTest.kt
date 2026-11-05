package dev.keething.vault.opening

import dev.keething.domain.secret.Secret
import dev.keething.domain.vault.EntryChanges
import dev.keething.domain.vault.VaultEdit
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.kdbx.xml.KdbxDocument
import dev.keething.kdbx.xml.KdbxEntry
import dev.keething.kdbx.xml.KdbxGroup
import dev.keething.kdbx.xml.KdbxMeta
import dev.keething.vault.saving.SaveResult
import dev.keething.vault.saving.VaultSaver
import dev.keething.vault.session.FakeStore
import dev.keething.vault.session.fixtureBytes
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The templates group (keething-spec groups.templates): KeePassDX's seven forms, and the file's other cases. */
class TemplatesGroupTest {
    private fun key() = DatabaseKey.of(Secret.of("pw"))

    private val keepassdxTemplates = setOf("Bank", "Cryptocurrency wallet", "Debit / Credit Card", "Email", "ID Card", "Notes", "Wi-Fi")

    // spec: groups.templates/AC1
    // spec: groups.templates/AC2
    @Test
    fun keepassdxsTemplatesAreSetApartWithTheirGroup() {
        val vault = assertIs<OpenResult.Opened>(VaultOpener().open(FakeStore(fixtureBytes("interop/keepassdx-4.5.8")), key())).opened.vault

        assertEquals(keepassdxTemplates, vault.templates.map { it.title }.toSet())
        assertEquals("Templates", vault.templatesGroup?.name)
        assertTrue(vault.entries.none { it.title in keepassdxTemplates }, "not in the list, search or the counts")
        assertTrue(vault.groups.none { it.name == "Templates" }, "not a group to choose")
        assertTrue(vault.entries.any { it.title == "Main" })
    }

    // spec: groups.templates/AC1
    @Test
    fun onlyAGroupOutsideTheRootAndTheBinIsTheTemplatesGroup() {
        fun group(uuid: String, vararg ancestors: String) = KdbxGroup(uuid, uuid, ancestors.drop(1).toList() + uuid, ancestors.toList() + uuid)
        val groups = listOf(KdbxGroup("R", "Root", emptyList(), listOf("R")), group("T", "R"), group("BIN", "R"), group("B", "R", "BIN"))
        val entries = listOf(KdbxEntry("t", emptyList(), ancestorGroupUuids = listOf("R", "T")), KdbxEntry("r", emptyList(), ancestorGroupUuids = listOf("R")))
        fun assembled(templates: String) =
            VaultAssembler.assemble(KdbxDocument(KdbxMeta(recycleBinUuid = "BIN", templatesGroupUuid = templates), groups, entries), emptyList())

        assertEquals(listOf("t"), assembled("T").templates.map { it.id })
        for (none in listOf("", "AAAAAAAAAAAAAAAAAAAAAA==", "R", "BIN", "B", "gone")) {
            val vault = assembled(none)
            assertTrue(vault.templates.isEmpty(), none)
            assertNull(vault.templatesGroup, none)
            assertEquals(setOf("t", "r"), vault.entries.map { it.id }.toSet(), none)
        }
    }

    // spec: groups.templates/AC4
    @Test
    fun aSaveWritesTheTemplatesBackAsTheyWere() {
        val store = FakeStore(fixtureBytes("interop/keepassdx-4.5.8"))
        val upgraded = assertIs<SaveResult.Saved>(VaultSaver(store) { _, _, _ -> }.upgrade(key(), Instant.now())).opened.vault
        val main = upgraded.entries.single { it.title == "Main" }

        val saved = VaultSaver(store) { _, _, _ -> }
            .save(VaultEdit.Update(main.id, main.lastModified, EntryChanges(notes = "Edited on the phone.")), key(), Instant.now())

        val after = assertIs<SaveResult.Saved>(saved).opened.vault
        assertEquals(upgraded.templates, after.templates)
        assertEquals(upgraded.templatesGroup, after.templatesGroup)
    }
}
