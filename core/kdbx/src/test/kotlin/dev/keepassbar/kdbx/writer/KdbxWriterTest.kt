package dev.keepassbar.kdbx.writer

import dev.keepassbar.domain.secret.Secret
import dev.keepassbar.kdbx.Fixtures
import dev.keepassbar.kdbx.KeePassXcCli
import dev.keepassbar.kdbx.editing.EditableDatabase
import dev.keepassbar.kdbx.key.DatabaseKey
import dev.keepassbar.kdbx.reader.Header
import dev.keepassbar.kdbx.reader.KdbxReader
import dev.keepassbar.kdbx.reader.KdbxTemplate
import dev.keepassbar.kdbx.tree.XElement
import dev.keepassbar.kdbx.tree.XmlTree
import dev.keepassbar.kdbx.tree.assertSameTree
import dev.keepassbar.kdbx.tree.descendants
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Writing the database (SPEC §3.3; the Mac `KDBXWriteTests` and `DerivedKeyTests` expectations). */
class KdbxWriterTest {
    private val folder: File = Files.createTempDirectory("kdbx-write").toFile()

    @AfterTest
    fun deleteFolder() {
        folder.deleteRecursively()
    }

    private fun key() = DatabaseKey.fromPassword(Secret.of(Fixtures.PASSWORD))

    private fun write(editable: EditableDatabase, key: DatabaseKey, template: KdbxTemplate = editable.template): ByteArray =
        KdbxWriter.write(editable.root, editable.binaries, editable.binaryFlags, template, key)

    @Test
    fun aesArgon2dReadAndWrittenBackIsTheSameDatabaseWithNewSeeds() = writtenBackTheSame("kdbx4-aes-argon2d")

    @Test
    fun chaCha20Argon2idReadAndWrittenBackIsTheSameDatabaseWithNewSeeds() = writtenBackTheSame("kdbx4-chacha20-argon2id")

    @Test
    fun aesAesKdfReadAndWrittenBackIsTheSameDatabaseWithNewSeeds() = writtenBackTheSame("kdbx4-aes-aeskdf")

    private fun writtenBackTheSame(name: String) {
        val original = Fixtures.bytes("$name.kdbx")
        val key = key()
        val editable = KdbxReader.openForEditing(original, key)

        val written = write(editable, key)

        assertFalse(written.contentEquals(original))
        assertFalse(written.copyOf(200).contentEquals(original.copyOf(200)), "a new master seed")
        val before = KdbxReader.open(original, key())
        val after = KdbxReader.open(written, key)
        assertNull(after.keyDerivation, "the KDF salt is kept: the key derived for the original opens it")
        assertEquals(before.document.meta, after.document.meta)
        assertEquals(before.document.groups, after.document.groups)
        assertEquals(before.document.entries, after.document.entries)
        assertEquals(before.binaries, after.binaries, "attachments byte for byte")
        assertEquals(editable.binaryFlags, KdbxReader.openForEditing(written, key).binaryFlags)
        assertEquals(KdbxReader.format(original), KdbxReader.format(written))
        assertContentEquals(Header.parse(original).kdf.identity, Header.parse(written).kdf.identity)
    }

    @Test
    fun theVersionGoesFrom40To41OnlyWithPreviousParentGroup() {
        val key = key()
        val editable = KdbxReader.openForEditing(Fixtures.bytes("kdbx4-aes-argon2d.kdbx"), key)

        assertEquals("4.0", KdbxReader.format(write(editable, key)).version)
        val entry = editable.root.first("Root")!!.first("Group")!!.descendants().first { it.name == "Entry" }
        entry.append(XElement("PreviousParentGroup", "AAAAAAAAAAAAAAAAAAAAAA=="))
        assertEquals("4.1", KdbxReader.format(write(editable, key)).version)
    }

    @Test
    fun anUncompressedFileStaysUncompressed() {
        val key = key()
        val editable = KdbxReader.openForEditing(Fixtures.bytes("kdbx4-chacha20-argon2id.kdbx"), key)

        val written = write(editable, key, editable.template.copy(compressed = false))

        assertFalse(KdbxReader.format(written).compressed)
        assertEquals(KdbxReader.open(Fixtures.bytes("kdbx4-chacha20-argon2id.kdbx"), key).document.entries,
            KdbxReader.open(written, key).document.entries)
    }

    @Test
    fun thePublicCustomDataGoesBackAsItWas() {
        val key = key()
        val editable = KdbxReader.openForEditing(Fixtures.bytes("kdbx4-aes-aeskdf.kdbx"), key)
        val custom = byteArrayOf(0x00, 0x01, 0x42, 0x01, 0x00, 0x00, 0x00, 0x6B, 0x01, 0x00, 0x00, 0x00, 0x76, 0x00)

        val written = write(editable, key, editable.template.copy(publicCustomData = custom))

        assertContentEquals(custom, Header.parse(written).template.publicCustomData)
        assertNotNull(KdbxReader.open(written, key).document)
    }

    @Test
    fun keepassxcCliExportsTheWrittenFileExactlyAsTheOriginal() {
        if (!KeePassXcCli.isInstalled) return
        val original = File(folder, "original.kdbx").apply { writeBytes(Fixtures.bytes("kdbx4-aes-argon2d.kdbx")) }
        val key = key()
        val written = File(folder, "written.kdbx").apply {
            writeBytes(write(KdbxReader.openForEditing(original.readBytes(), key), key))
        }

        assertSameTree(export(original), export(written))
    }

    @Test
    fun keepassxcCliOpensEveryFixtureWritten() {
        if (!KeePassXcCli.isInstalled) return
        for (name in listOf("kdbx4-aes-argon2d", "kdbx4-chacha20-argon2id", "kdbx4-aes-aeskdf")) {
            val key = key()
            val written = File(folder, "$name.kdbx").apply {
                writeBytes(write(KdbxReader.openForEditing(Fixtures.bytes("$name.kdbx"), key), key))
            }

            val shown = KeePassXcCli.run("show", "-s", "-a", "Password", written.path, "Web & Mail/Entry <one> ü")

            assertEquals(0, shown.code, shown.err)
            assertEquals("p&ss<w>rd \"ü\" 🔑\n", shown.text, name)
        }
    }

    /** The CLI's XML export; its `CustomData` items come in a random order, so they are sorted. */
    private fun export(file: File): XElement {
        val result = KeePassXcCli.run("export", "-f", "xml", file.path)
        assertEquals(0, result.code, result.err)
        val root = XmlTree.parse(result.out)
        fun sortCustomData(element: XElement) {
            if (element.name == "CustomData") {
                val items = element.all("Item").sortedBy { it.first("Key")?.text ?: "" }
                element.children.clear()
                element.children.addAll(items)
            }
            element.elements.forEach(::sortCustomData)
        }
        sortCustomData(root)
        assertTrue(root.descendants().isNotEmpty())
        return root
    }
}
