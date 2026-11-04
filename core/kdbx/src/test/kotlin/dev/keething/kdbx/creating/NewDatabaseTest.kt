package dev.keething.kdbx.creating

import dev.keething.domain.secret.Secret
import dev.keething.kdbx.Fixtures
import dev.keething.kdbx.KeePassXcCli
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.kdbx.key.KeyFile
import dev.keething.kdbx.reader.Header
import dev.keething.kdbx.reader.KdbxException
import dev.keething.kdbx.reader.KdbxFormat
import dev.keething.kdbx.reader.KdbxFormat.Cipher
import dev.keething.kdbx.reader.KdbxFormat.Kdf
import dev.keething.kdbx.reader.KdbxReader
import dev.keething.kdbx.reader.VariantDictionary
import dev.keething.kdbx.xml.KdbxTime
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A new database file (keething-spec kdbx.new-file; the Mac's `NewDatabaseTests`, "The format" and "The XML"): its
 * format, its first XML, the file written and read back. The KDFs are cheap (1 MiB, a few iterations).
 */
class NewDatabaseTest {
    private val folder: File = Files.createTempDirectory("keething-new").toFile()
    private val now = Instant.ofEpochSecond(1_793_000_000)

    @AfterTest
    fun deleteFolder() {
        folder.deleteRecursively()
    }

    private fun pw() = DatabaseKey.of(Secret.of(Fixtures.PASSWORD))

    private fun write(cipher: Cipher, kdf: Kdf, name: String? = "Work", fileName: String = "Work.kdbx", key: DatabaseKey = pw()) =
        NewDatabase.file(name, fileName, cipher, kdf, key, now)

    // The format

    @Test
    fun everyCipherAndKdfIsWrittenAsKdbx40AndReadBackInTheFormatAskedFor() {
        for (cipher in listOf(Cipher.AES256, Cipher.CHACHA20)) {
            for (kdf in listOf(CHEAP_ARGON2D, CHEAP_ARGON2ID, CHEAP_AES)) {
                val file = write(cipher, kdf)

                assertEquals(KdbxFormat("4.0", cipher, kdf, compressed = true), KdbxReader.format(file), "$cipher $kdf")
                val opened = KdbxReader.open(file, pw())
                assertEquals("Work", opened.document.meta.databaseName)
                assertTrue(opened.document.entries.isEmpty() && opened.binaries.isEmpty())
            }
        }
    }

    @Test
    fun everyTemplateHasAFresh32ByteSaltOrAesKdfSeed() {
        for (kdf in listOf(CHEAP_ARGON2D, CHEAP_AES)) {
            val a = parameters(NewDatabase.template(Cipher.AES256, kdf))
            val b = parameters(NewDatabase.template(Cipher.AES256, kdf))
            val salt = assertNotNull(a.bytes("S"))
            assertEquals(32, salt.size)
            assertNotEquals(salt.toList(), b.bytes("S")?.toList(), "two new databases never share a salt")
        }
    }

    @Test
    fun theKdfParametersAsKeePassWritesThem() {
        val argon = NewDatabase.template(Cipher.AES256, Kdf.Argon2(Kdf.Argon2.Variant.ID, 7, 64L shl 20, 4))
        val a = parameters(argon)
        assertEquals("9e298b1956db4773b23dfc3ec6f0a1e6", Header.hex(a.bytes("\$UUID")!!))
        assertEquals(7, a.uint64("I"))
        assertEquals(64L shl 20, a.uint64("M"))
        assertEquals(4, a.uint32("P"))
        assertEquals(0x13, a.uint32("V"))
        assertEquals(listOf("\$UUID", "V", "S", "P", "M", "I"), a.keys(), "in KeePass's order")
        val aes = parameters(NewDatabase.template(Cipher.AES256, Kdf.AesKdf(123_456)))
        // KeePass 2.x knows only its own id; KeePassXC's KDBX 4 variant id would not open there.
        assertEquals("c9d9f39a628a4460bf740d08c18a4fea", Header.hex(aes.bytes("\$UUID")!!))
        assertEquals(123_456, aes.uint64("R"))
        assertEquals(listOf("\$UUID", "R", "S"), aes.keys())
        assertEquals(4, argon.major)
        assertEquals(0, argon.minor)
        assertTrue(argon.publicCustomData == null && argon.compressed)
        val d = parameters(NewDatabase.template(Cipher.AES256, CHEAP_ARGON2D))
        assertEquals("ef636ddf8c29444b91f7a9a403e30a0c", Header.hex(d.bytes("\$UUID")!!))
    }

    @Test
    fun twofishAndParametersKeePassWouldNotRunAreRefused() {
        assertFailsWith<KdbxException.UnsupportedCipher> { NewDatabase.template(Cipher.TWOFISH, CHEAP_ARGON2D) }
        for (kdf in listOf(
            argon2d(iterations = 0, memory = 1L shl 20, lanes = 1),
            argon2d(iterations = 1, memory = 1L shl 20, lanes = 0),
            argon2d(iterations = 1L shl 32, memory = 1L shl 20, lanes = 1), // over 2³²−1
            argon2d(iterations = 1, memory = 1L shl 30, lanes = 1 shl 24), // over 2²⁴−1 lanes
            Kdf.Argon2(Kdf.Argon2.Variant.ID, 1, 1000, 1), // not whole KiB
            Kdf.Argon2(Kdf.Argon2.Variant.ID, 1, 8L shl 10, 2), // under 8 KiB per lane
            argon2d(iterations = 1, memory = 2L shl 30, lanes = 1), // 2 GiB: over KeePass 2.x's int.MaxValue
            Kdf.AesKdf(0),
        )) {
            assertFailsWith<KdbxException.UnsupportedKdf>("$kdf") { NewDatabase.template(Cipher.AES256, kdf) }
        }
    }

    @Test
    fun argon2MemoryUpToKeePassLimitIsWritten() {
        NewDatabase.template(Cipher.AES256, argon2d(iterations = 1, memory = (2L shl 30) - 1024, lanes = 1))
    }

    @Test
    fun iterationsUpToKeePassLimitAreWrittenAndNoMore() {
        NewDatabase.check(argon2d(iterations = (1L shl 32) - 1, memory = 1L shl 20, lanes = 1))
        NewDatabase.template(Cipher.AES256, argon2d(iterations = (1L shl 32) - 1, memory = 1L shl 20, lanes = 1))
        assertFailsWith<KdbxException.UnsupportedKdf> {
            NewDatabase.template(Cipher.AES256, argon2d(iterations = 1L shl 32, memory = 1L shl 20, lanes = 1))
        }
    }

    // The XML

    @Test
    fun theXmlAsKeePassXcWritesItGeneratorKeethingTheNameTypedNoKeePassXcCustomData() {
        val root = NewDatabase.document("  Work  ", "work.kdbx", now)
        val meta = assertNotNull(root.first("Meta"))
        assertEquals("Keething", meta.first("Generator")?.text)
        assertEquals("Work", meta.first("DatabaseName")?.text)
        assertEquals(
            listOf(
                "Generator", "DatabaseName", "DatabaseNameChanged", "DatabaseDescription", "DatabaseDescriptionChanged",
                "DefaultUserName", "DefaultUserNameChanged", "MaintenanceHistoryDays", "Color", "MasterKeyChanged",
                "MasterKeyChangeRec", "MasterKeyChangeForce", "MemoryProtection", "CustomIcons", "RecycleBinEnabled",
                "RecycleBinUUID", "RecycleBinChanged", "EntryTemplatesGroup", "EntryTemplatesGroupChanged",
                "LastSelectedGroup", "LastTopVisibleGroup", "HistoryMaxItems", "HistoryMaxSize", "CustomData",
            ),
            meta.elements.map { it.name },
        )
        assertTrue(meta.first("CustomData")!!.elements.isEmpty(), "no KPXC_RANDOM_SLUG, no _LAST_MODIFIED")
        assertEquals(
            listOf("ProtectTitle=False", "ProtectUserName=False", "ProtectPassword=True", "ProtectURL=False", "ProtectNotes=False"),
            meta.first("MemoryProtection")!!.elements.map { "${it.name}=${it.text}" },
        )
        assertEquals("True", meta.first("RecycleBinEnabled")?.text)
        assertEquals("AAAAAAAAAAAAAAAAAAAAAA==", meta.first("RecycleBinUUID")?.text)
        assertEquals("AAAAAAAAAAAAAAAAAAAAAA==", meta.first("EntryTemplatesGroup")?.text)
        assertEquals("365", meta.first("MaintenanceHistoryDays")?.text)
        assertEquals("", meta.first("Color")?.text)
        assertEquals("-1", meta.first("MasterKeyChangeRec")?.text)
        assertEquals("-1", meta.first("MasterKeyChangeForce")?.text)
        assertEquals("10", meta.first("HistoryMaxItems")?.text)
        assertEquals("6291456", meta.first("HistoryMaxSize")?.text)
        assertEquals(KdbxTime.base64(now), meta.first("MasterKeyChanged")?.text, "KDBX 4: base64 times")
        val groups = root.first("Root")!!.all("Group")
        assertEquals(1, groups.size)
        val group = groups.single()
        assertEquals(
            listOf("UUID", "Name", "Notes", "IconID", "Times", "IsExpanded", "DefaultAutoTypeSequence", "EnableAutoType",
                "EnableSearching", "LastTopVisibleEntry"),
            group.elements.map { it.name },
        )
        assertEquals("Work", group.first("Name")?.text)
        assertEquals("48", group.first("IconID")?.text)
        assertEquals("True", group.first("IsExpanded")?.text)
        assertEquals("null", group.first("EnableAutoType")?.text)
        assertEquals("null", group.first("EnableSearching")?.text)
        assertTrue(group.all("Entry").isEmpty() && group.all("Group").isEmpty())
        assertEquals(16, Base64.getDecoder().decode(group.first("UUID")!!.text).size)
        assertEquals(listOf("Group", "DeletedObjects"), root.first("Root")!!.elements.map { it.name })
        assertEquals(listOf("Meta", "Root"), root.elements.map { it.name })
        assertEquals("KeePassFile", root.name)
    }

    @Test
    fun withoutANameDatabaseNameStaysEmptyAndTheRootGroupTakesTheFileName() {
        for (name in listOf(null, "", "   ")) {
            val root = NewDatabase.document(name, "Family Shared.kdbx", now)
            assertEquals("", root.first("Meta")!!.first("DatabaseName")!!.text)
            assertEquals("Family Shared", root.first("Root")!!.first("Group")!!.first("Name")!!.text)
        }
        assertEquals("notes", NewDatabase.rootGroupName(null, "notes.KDBX"))
        assertEquals("Family", NewDatabase.databaseName("Fam\nily\t\u0007"), "control characters go: XML cannot hold most")
        assertEquals("x", NewDatabase.rootGroupName("\u0001\n", "x.kdbx"))
        assertEquals("archive.2024", NewDatabase.rootGroupName(null, "archive.2024"), "only .kdbx goes")
        assertEquals("Root", NewDatabase.rootGroupName(null, ".kdbx"), "never an empty root group")
        assertEquals("Work", NewDatabase.databaseName("W\uD800ork\uFFFE\uFFFF"), "a lone surrogate and non-characters go too")
        assertEquals("Work 🔑", NewDatabase.databaseName("Work 🔑"), "a pair stays")
    }

    @Test
    fun twoDocumentsNeverShareARootGroupId() {
        val a = NewDatabase.document("A", "a.kdbx", now)
        val b = NewDatabase.document("A", "a.kdbx", now)
        assertNotEquals(a.first("Root")!!.first("Group")!!.first("UUID")!!.text, b.first("Root")!!.first("Group")!!.first("UUID")!!.text)
    }

    // The file

    @Test
    fun theFileOpensWithItsNameOneRootGroupOfThatNameAndNothingInIt() {
        val file = write(Cipher.AES256, CHEAP_ARGON2D, name = "Fresh", fileName = "Fresh.kdbx")

        val opened = KdbxReader.open(file, pw())

        assertEquals("Fresh", opened.document.meta.databaseName)
        assertEquals(listOf("Fresh"), opened.document.groups.map { it.name })
        assertTrue(opened.document.entries.isEmpty())
        assertEquals("", opened.document.meta.recycleBinUuid.trim('A', '='), "no bin yet")
    }

    @Test
    fun theKeyIsDerivedOnceAndKeptForTheFirstUnlock() {
        val key = pw()
        val file = write(Cipher.AES256, CHEAP_ARGON2D, key = key)

        assertEquals(null, KdbxReader.open(file, key).keyDerivation, "the key derived for the write opens it")
    }

    @Test
    fun aNameWithALineBreakIsWrittenWithoutIt() {
        val file = write(Cipher.AES256, CHEAP_AES, name = "Wo\nrk")
        assertEquals("Work", KdbxReader.open(file, pw()).document.meta.databaseName)
    }

    @Test
    fun aKeyFileAndAPasswordOrAKeyFileAlone() {
        val keyFile = KeyFile.newKey()
        for (password in listOf(Secret.of("pw"), null)) {
            val file = write(Cipher.AES256, CHEAP_AES, key = DatabaseKey.of(password, keyFile))
            KdbxReader.open(file, DatabaseKey.of(password, keyFile))
            assertFailsWith<KdbxException.WrongPassword> { KdbxReader.open(file, pw()) }
        }
    }

    // keepassxc-cli opens what was made (when installed)

    @Test
    fun keePassXcOpensAPasswordDatabaseAsMade() {
        if (!KeePassXcCli.isInstalled) return
        val database = File(folder, "Work.kdbx").apply { writeBytes(write(Cipher.CHACHA20, CHEAP_ARGON2ID)) }

        val info = KeePassXcCli.run("db-info", database.path)

        assertEquals(0, info.code, info.err)
        for (line in listOf("Name: Work", "ChaCha20", "Argon2id", "Number of entries: 0")) {
            assertTrue(line in info.text, info.text)
        }
        assertEquals(0, KeePassXcCli.run("ls", database.path).code)
        assertNotEquals(0, KeePassXcCli.run("ls", database.path, input = "not it\n").code, "a wrong password does not open it")
    }

    @Test
    fun keePassXcOpensAPasswordAndANewKeyFileAndAKeyFileOnly() {
        if (!KeePassXcCli.isInstalled) return
        val key = KeyFile.newKey()
        val keyFile = File(folder, "work.keyx").apply { writeBytes(KeyFile.contents(key)) }
        val both = File(folder, "Both.kdbx").apply {
            writeBytes(write(Cipher.AES256, CHEAP_ARGON2D, name = "Both", key = DatabaseKey.of(Secret.of("pw"), key)))
        }
        val only = File(folder, "Only.kdbx").apply {
            writeBytes(write(Cipher.AES256, CHEAP_AES, name = "Only", key = DatabaseKey.of(null, key)))
        }

        val a = KeePassXcCli.run("ls", "-k", keyFile.path, both.path)
        assertEquals(0, a.code, a.err)
        assertNotEquals(0, KeePassXcCli.run("ls", both.path).code, "the password alone does not open it")
        val b = KeePassXcCli.run("db-info", "-k", keyFile.path, "--no-password", only.path, input = "")
        assertEquals(0, b.code, b.err)
        assertTrue("Name: Only" in b.text, b.text)
    }

    // The fixtures made by the Mac's creator (`created/`)

    @Test
    fun eachFixtureMadeByKeethingOpensWithItsNameOneRootGroupOfThatNameNothingInItItsCipherAndKdf() {
        val created = KeyFile.key(Fixtures.bytes("created/created.keyx"))
        val expected = listOf(
            Triple("created-chacha20-argon2id", "Made by Keething · ChaCha20", KdbxFormat("4.0", Cipher.CHACHA20, Kdf.Argon2(Kdf.Argon2.Variant.ID, 333, 1L shl 20, 4), true)),
            Triple("created-aes-aeskdf", "Made by Keething · AES-KDF", KdbxFormat("4.0", Cipher.AES256, Kdf.AesKdf(2_058_992), true)),
            Triple("created-keyfile", "Made by Keething · key file", KdbxFormat("4.0", Cipher.AES256, argon2d(331, 1L shl 20, 4), true)),
            Triple("created-keyfile-only", "Made by Keething · key file only", KdbxFormat("4.0", Cipher.AES256, argon2d(334, 1L shl 20, 4), true)),
        )
        for ((name, title, format) in expected) {
            val key = when (name) {
                "created-keyfile" -> DatabaseKey.of(Secret.of("pw"), created)
                "created-keyfile-only" -> DatabaseKey.of(null, created)
                else -> pw()
            }
            val opened = KdbxReader.open(Fixtures.bytes("created/$name.kdbx"), key)
            assertEquals(format, opened.format, name)
            assertEquals(title, opened.document.meta.databaseName, name)
            assertEquals(listOf(title), opened.document.groups.map { it.name }, name)
            assertTrue(opened.document.entries.isEmpty(), name)
        }
    }

    @Test
    fun theDefaultMadeOnTheMacIs64MiB4LanesAnd115Iterations() {
        // Opened on the phone, where its time is measured (the Mac's 1 s); here only its format.
        assertEquals(
            KdbxFormat("4.0", Cipher.AES256, argon2d(115, 64L shl 20, 4), true),
            KdbxReader.format(Fixtures.bytes("created/created-aes-argon2d.kdbx")),
        )
    }

    @Test
    fun theFixtureKeyFileIsKeePassXcsByteForByteWithItsKnownKey() {
        val contents = Fixtures.bytes("created/created.keyx")
        val key = KeyFile.key(contents)
        assertEquals("A7DC6EDBB24151B106BBF27DB7F47E9849AED8C25D59CBB40108A6C3C8FED34C", key.useBytes { Header.hex(it) }.uppercase())
        assertContentEquals(contents, KeyFile.contents(key))
        assertEquals(
            "6f4930e5b34edf27537172f5f8b9cd54493ca81da9ba777c422df932c4a5eb9b",
            Header.hex(DatabaseKey.of(Secret.of("pw"), key).compositeForTests()),
        )
        assertEquals(
            "2194b3624283c33434096632e21c56e633b5497ce1e04b103d33075f93fd2046",
            Header.hex(DatabaseKey.of(null, key).compositeForTests()),
        )
    }

    private fun parameters(template: dev.keething.kdbx.reader.KdbxTemplate) = VariantDictionary.parse(template.kdf.identity)

    companion object {
        fun argon2d(iterations: Long, memory: Long, lanes: Int) = Kdf.Argon2(Kdf.Argon2.Variant.D, iterations, memory, lanes)

        val CHEAP_ARGON2D = argon2d(2, 1L shl 20, 2)
        val CHEAP_ARGON2ID = Kdf.Argon2(Kdf.Argon2.Variant.ID, 2, 1L shl 20, 2)
        val CHEAP_AES = Kdf.AesKdf(1000)
    }
}
