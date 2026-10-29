package dev.keething.kdbx.key

import dev.keething.domain.secret.Secret
import dev.keething.kdbx.Fixtures
import dev.keething.kdbx.KeePassXcCli
import dev.keething.kdbx.reader.KdbxException
import dev.keething.kdbx.reader.KdbxReader
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Key files (keething-spec kdbx.key-files; the Mac's `KeyFileTests`). The fixtures in `fixtures/keyfiles` are
 * fictitious: `kdbx4-aes-argon2d.kdbx` (password "pw") given a key file of each kind by keepassxc-cli 2.7.12, so
 * KeePassXC's reading of each file is what Keething must match: `keyx.keyx` (XML 2.0, written by KeePassXC),
 * `v1.key` (XML 1.0, base64), `raw32.key` (32 random bytes), `hex64.key` (64 hex digits), `hashed.txt` (any other
 * file: its SHA-256); `keyfile-only.kdbx` has `keyx.keyx` and no password.
 */
class KeyFileTest {
    private val folder: File = Files.createTempDirectory("keething-keyfile").toFile()

    @AfterTest
    fun cleanUp() {
        folder.deleteRecursively()
    }

    private fun contents(name: String) = Fixtures.bytes("keyfiles/$name")

    private fun key(contents: ByteArray): ByteArray = KeyFile.key(contents).useBytes { it.copyOf() }

    private fun hex(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    /** The key KeePassXC wrote into `keyx.keyx`. */
    private val keyxKey = hex("C4C09C33A0DB0F631381516DFB11FB6BE9EBC7626CC34F3555706CD798648BAF")
    private val good = "C4C09C33A0DB0F631381516DFB11FB6BE9EBC7626CC34F3555706CD798648BAF"

    private fun keyx(data: String, hash: String? = "38D4B997", version: String = "2.0"): ByteArray {
        val attribute = hash?.let { " Hash=\"$it\"" } ?: ""
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<KeyFile><Meta><Version>$version</Version></Meta>" +
            "<Key><Data$attribute>$data</Data></Key></KeyFile>\n").encodeToByteArray()
    }

    // Reading a key file

    @Test
    fun keepassXcsKeyxGivesTheKeyInItsHexWithItsHashChecked() {
        assertContentEquals(keyxKey, key(contents("keyx.keyx")))
    }

    @Test
    fun xml10GivesTheKeyInItsBase64() {
        val text = contents("v1.key").decodeToString()
        val base64 = text.substringAfter("<Data>").substringBefore("</Data>")

        assertContentEquals(Base64.getDecoder().decode(base64), key(contents("v1.key")))
        assertContentEquals(hex("496BFBA93F10360F215A1E2766D0BF51DFDF2587B28CD5330E6EAC7F9216F84B"), key(contents("v1.key")))
    }

    @Test
    fun thirtyTwoBytesAreTheKeyAsTheyAreAndSixtyFourHexDigitsTheKeyInHex() {
        assertContentEquals(contents("raw32.key"), key(contents("raw32.key")))
        val digits = contents("hex64.key")
        val expected = hex("59DBF8AEE9CD4AE9C61466EFB4D4D68104BA08CD7BA79261ACCF2D2042D9A486")
        assertContentEquals(expected, key(digits))
        assertContentEquals(expected, key(digits.decodeToString().uppercase().encodeToByteArray()), "either case")
    }

    @Test
    fun anyOtherFileIsHashedWholeXmlThatIsNotAKeyFileToo() {
        val text = contents("hashed.txt")
        assertContentEquals(sha256(text), key(text))
        assertContentEquals(hex("C161067B02375015390153C6E3986A0CEA086743AF9F21D6709CAF67EBA41F7B"), key(text))
        val html = "<html><body>a web page, not a key file</body></html>".encodeToByteArray()
        assertContentEquals(sha256(html), key(html))
        val thirtyTwo = "<html><body>a page</body></html>".encodeToByteArray()
        assertEquals(32, thirtyTwo.size)
        assertContentEquals(thirtyTwo, key(thirtyTwo), "32 bytes of XML that is no KeyFile: the key as it is")
        val sixtyFour = "z".repeat(64).encodeToByteArray()
        assertContentEquals(sha256(sixtyFour), key(sixtyFour), "64 characters, not hex")
    }

    @Test
    fun blanksBeforeTheXmlDeclarationAreNotXmlAndTheFileIsHashed() {
        val shifted = "  ".encodeToByteArray() + keyx(good)
        assertContentEquals(sha256(shifted), key(shifted))
    }

    @Test
    fun aKeyFileWithADoctypeIsNotReadAsXmlAndIsHashed() {
        val text = ("<?xml version=\"1.0\"?>\n<!DOCTYPE KeyFile [<!ENTITY k \"$good\">]>\n" +
            "<KeyFile><Meta><Version>2.0</Version></Meta><Key><Data>&k;</Data></Key></KeyFile>\n").encodeToByteArray()
        assertContentEquals(sha256(text), key(text))
    }

    @Test
    fun version20InLowerCaseOtherLineBreaksAndAByteOrderMarkGiveTheSameKey() {
        val loose = "\n  c4c09c33a0db0f63\r\n1381516dfb11fb6b  e9ebc7626cc34f35\t55706cd798648baf\n"
        assertContentEquals(keyxKey, key(keyx(loose, hash = "38d4b997")))
        assertContentEquals(keyxKey, key(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + keyx(loose)))
        assertContentEquals(keyxKey, key(keyx(loose, hash = null)), "no Hash: nothing to check")
        assertContentEquals(keyxKey, key("\n".encodeToByteArray() + keyx(loose).decodeToString().substringAfter("?>\n").encodeToByteArray()),
            "a line break before <KeyFile> without a declaration is XML still")
    }

    @Test
    fun whatLooksLikeAKeyFileButFailsIsAnErrorNeverHashedInstead() {
        assertFailsWith<KeyFileException.HashMismatch> { KeyFile.key(keyx(good, hash = "38D4B998")) }
        assertFailsWith<KeyFileException.HashMismatch> { KeyFile.key(keyx("D" + good.drop(1))) }
        assertEquals("3.0", assertFailsWith<KeyFileException.UnsupportedVersion> { KeyFile.key(keyx(good, version = "3.0")) }.version)
        assertEquals("none", assertFailsWith<KeyFileException.UnsupportedVersion> { KeyFile.key(keyx(good, version = "")) }.version)
        assertEquals("Key/Data is not hex", assertFailsWith<KeyFileException.Malformed> { KeyFile.key(keyx("XY" + good.drop(2))) }.why)
        assertEquals(
            "the key is 31 bytes, not 32",
            assertFailsWith<KeyFileException.Malformed> { KeyFile.key(keyx(good.dropLast(2), hash = null)) }.why,
        )
        assertEquals(
            "Key/Data is not base64",
            assertFailsWith<KeyFileException.Malformed> { KeyFile.key(keyx("%%%", hash = null, version = "1.00")) }.why,
        )
        assertEquals(
            "the key is 3 bytes, not 32",
            assertFailsWith<KeyFileException.Malformed> { KeyFile.key(keyx("AQID", hash = null, version = "1.00")) }.why,
        )
        assertEquals(
            "no Key/Data",
            assertFailsWith<KeyFileException.Malformed> {
                KeyFile.key("<KeyFile><Meta><Version>2.0</Version></Meta></KeyFile>".encodeToByteArray())
            }.why,
        )
        assertFailsWith<KeyFileException.Empty> { KeyFile.key(ByteArray(0)) }
    }

    @Test
    fun theKeyIsACopyTheCallerWipes() {
        val read = KeyFile.key(contents("keyx.keyx"))
        assertEquals(32, read.size)
        read.wipe()
        assertTrue(read.isWiped)
        assertContentEquals(keyxKey, key(contents("keyx.keyx")), "read again from the same bytes")
        val raw = contents("raw32.key")
        val fromRaw = KeyFile.key(raw)
        raw.fill(0)
        assertTrue(fromRaw.useBytes { bytes -> bytes.any { it != 0.toByte() } }, "32 bytes: a copy, not the caller's buffer")
    }

    // The composite key (kdbx.key-files AC6, the vectors of its fixtures table)

    @Test
    fun theCompositeKeyIsSha256OverTheParts() {
        val pw = Secret.of("pw")
        val file = Secret.copyOf(keyxKey)
        val passwordHash = sha256("pw".encodeToByteArray())

        assertContentEquals(sha256(passwordHash), DatabaseKey.of(pw).compositeForTests())
        assertContentEquals(hex("ec7a74baa773cc5e99a439acfb045ad9396791c0a47ecb226ee4e3ae4a49bfbf"), DatabaseKey.of(pw).compositeForTests())
        assertContentEquals(sha256(passwordHash + keyxKey), DatabaseKey.of(pw, file).compositeForTests())
        assertContentEquals(hex("600bebc3130aada44767600641d9c4827eee610c2d623304cf0965d88287b9b0"), DatabaseKey.of(pw, file).compositeForTests())
        assertContentEquals(sha256(keyxKey), DatabaseKey.of(null, file).compositeForTests())
        assertContentEquals(hex("38d4b9976dcebbcb63e68b370fc33112bcd397bf5b5fd5f0874edb7f73ca668f"), DatabaseKey.of(null, file).compositeForTests())
        val emptyWithFile = DatabaseKey.of(Secret.empty(), file).compositeForTests()
        assertContentEquals(sha256(sha256(ByteArray(0)) + keyxKey), emptyWithFile, "an empty password is a password")
        assertFalse(emptyWithFile.contentEquals(DatabaseKey.of(null, file).compositeForTests()))
        assertFalse(pw.isWiped || file.isWiped, "the parts stay the caller's")
    }

    @Test
    fun aKeyNeedsAPasswordOrA32ByteKeyFile() {
        assertFailsWith<IllegalArgumentException> { DatabaseKey.of(null, null) }
        assertFailsWith<IllegalArgumentException> { DatabaseKey.of(null, Secret.copyOf(ByteArray(31))) }
    }

    @Test
    fun theFixturesCompositeKeys() {
        val table = mapOf(
            "v1.key" to "7cbb88d10dcac46998d95ca26c50743fabdebd39e764dd3542fe0d6d4d97a32b",
            "raw32.key" to "cb740628be99ad89c14f4cd392467700423db4322c8867777a1bc7b04e1c3265",
            "hex64.key" to "fa64819fc92f2b360c88b5763922f24812ed4bb6709a16052bcca1e9ebf85d4a",
            "hashed.txt" to "184afb302b71aacd05af18902c190f0a1facf8b2420071cf392a5963d3e17d0b",
        )
        for ((file, composite) in table) {
            assertContentEquals(hex(composite), DatabaseKey.of(Secret.of("pw"), KeyFile.key(contents(file))).compositeForTests(), file)
        }
    }

    // Opening the fixtures

    private val pairs = listOf(
        "keyfile-keyx" to "keyx.keyx",
        "keyfile-v1" to "v1.key",
        "keyfile-raw32" to "raw32.key",
        "keyfile-hex64" to "hex64.key",
        "keyfile-hashed" to "hashed.txt",
    )

    @Test
    fun eachFixtureOpensWithThePasswordAndItsKeyFileAndNotWithoutIt() {
        for ((database, file) in pairs) {
            val data = contents("$database.kdbx")
            val opened = KdbxReader.open(data, DatabaseKey.of(Secret.of("pw"), KeyFile.key(contents(file))))
            assertTrue(opened.document.entries.isNotEmpty(), database)
            assertFailsWith<KdbxException.WrongPassword>(database) { KdbxReader.open(data, DatabaseKey.of(Secret.of("pw"))) }
            assertFailsWith<KdbxException.WrongPassword>(database) {
                KdbxReader.open(data, DatabaseKey.of(Secret.of("pw"), Secret.copyOf(ByteArray(32) { 1 })))
            }
        }
    }

    @Test
    fun aDatabaseWithAKeyFileOnlyOpensWithItAloneNotWithAPasswordBesideIt() {
        val data = contents("keyfile-only.kdbx")
        val file = KeyFile.key(contents("keyx.keyx"))

        assertTrue(KdbxReader.open(data, DatabaseKey.of(null, file)).document.entries.isNotEmpty())
        assertFailsWith<KdbxException.WrongPassword> { KdbxReader.open(data, DatabaseKey.of(Secret.empty(), file)) }
    }

    // Saving into such a database (kdbx.key-files AC10)

    @Test
    fun aSaveIntoEachKeyFileDatabaseOpensAgainWithTheSameKey() {
        for ((database, file) in pairs + ("keyfile-only" to "keyx.keyx")) {
            val password = if (database == "keyfile-only") null else Secret.of("pw")
            val key = DatabaseKey.of(password, KeyFile.key(contents(file)))
            val written = KdbxReader.openForEditing(contents("$database.kdbx"), key).write(key).file

            assertTrue(KdbxReader.open(written, DatabaseKey.of(password, KeyFile.key(contents(file)))).document.entries.isNotEmpty(), database)
            if (password != null) {
                assertFailsWith<KdbxException.WrongPassword>(database) { KdbxReader.open(written, DatabaseKey.of(password)) }
            }
        }
    }

    @Test
    fun keepassxcCliOpensASaveIntoAKeyFileDatabase() {
        if (!KeePassXcCli.isInstalled) return
        for ((database, withPassword) in listOf("keyfile-keyx" to true, "keyfile-only" to false)) {
            val keyFile = File(folder, "keyx.keyx").apply { writeBytes(contents("keyx.keyx")) }
            val key = DatabaseKey.of(if (withPassword) Secret.of("pw") else null, KeyFile.key(keyFile.readBytes()))
            val written = File(folder, "$database.kdbx").apply {
                writeBytes(KdbxReader.openForEditing(contents("$database.kdbx"), key).write(key).file)
            }
            val arguments = listOf("show", "-s", "-a", "Password", "-k", keyFile.path) +
                (if (withPassword) emptyList() else listOf("--no-password")) +
                listOf(written.path, "Web & Mail/Entry <one> ü")

            val shown = KeePassXcCli.run(*arguments.toTypedArray(), input = if (withPassword) "pw\n" else "")

            assertEquals(0, shown.code, "$database: ${shown.err}")
            assertEquals("p&ss<w>rd \"ü\" 🔑\n", shown.text, database)
            if (withPassword) {
                val without = KeePassXcCli.run("ls", written.path)
                assertFalse(without.code == 0, "and not without the key file")
            }
        }
    }
}
