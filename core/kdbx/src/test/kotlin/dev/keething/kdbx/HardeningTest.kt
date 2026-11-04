package dev.keething.kdbx

import dev.keething.domain.secret.Secret
import dev.keething.domain.vault.CustomField
import dev.keething.domain.vault.EntryChanges
import dev.keething.domain.vault.FieldValue
import dev.keething.domain.vault.VaultEdit
import dev.keething.kdbx.editing.EntryLifecycle
import dev.keething.kdbx.editing.HostileFiles
import dev.keething.kdbx.editing.field
import dev.keething.kdbx.key.AppMemory
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.kdbx.key.WipedKeyException
import dev.keething.kdbx.reader.KdbxException
import dev.keething.kdbx.reader.KdbxReader
import dev.keething.kdbx.schema.SchemaCheck
import dev.keething.kdbx.tree.XmlTree
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * keething-spec kdbx.hardening against the phone's engine: the review of the Mac's engine (its Appendix A) rebuilt in
 * Kotlin, one test per finding the criteria hold the phone to.
 */
class HardeningTest {
    private val now = Instant.ofEpochSecond(1_900_000_000)
    private val folder: File = Files.createTempDirectory("hardening").toFile()

    @AfterTest
    fun cleanUp() {
        AppMemory.most = { Runtime.getRuntime().maxMemory() }
        folder.deleteRecursively()
    }

    private fun key() = DatabaseKey.of(Secret.of(Fixtures.PASSWORD))

    private fun fixture(name: String = "kdbx4-aes-aeskdf") = Fixtures.bytes("$name.kdbx")

    private fun password(file: ByteArray, key: DatabaseKey, id: String): String =
        assertIs<FieldValue.Protected>(KdbxReader.open(file, key).document.entries.single { it.uuid == id }.field("Password")).secret.reveal()

    // AC1, AC2: the key a save writes with

    // spec: kdbx.hardening/AC1
    @Test
    fun aSaveWritesWithTheKeyThatOpenedTheFileAndReadsItBackWithItLookingNothingUp() {
        // H1, deterministic: the key wiped as a lock wipes it, between the open and the write.
        val key = key()
        val editable = KdbxReader.openForEditing(fixture(), key)
        val id = editable.document.entries.first().uuid
        editable.apply(VaultEdit.Update(id, null, EntryChanges(password = Secret.of("new one"))), now)
        key.wipe()

        val written = editable.write()
        val reread = editable.readBack(written.file)

        assertEquals("new one", password(written.file, key(), id), "the real password opens it, not zeros")
        assertEquals(null, reread.keyDerivation, "read back with the key that opened the original: nothing derived")
        assertFailsWith<KdbxException.WrongPassword> { KdbxReader.open(written.file, DatabaseKey.of(Secret.of("\u0000\u0000"))) }
    }

    // spec: kdbx.hardening/AC1
    @Test
    fun theKeyThatOpenedAFileReadsBackOnlyAFileOfTheSameKeyDerivation() {
        val editable = KdbxReader.openForEditing(fixture("kdbx4-aes-aeskdf"), key())

        val other = assertFailsWith<KdbxException.Corrupt> { editable.readBack(fixture("kdbx4-aes-argon2d")) }

        assertContains(other.detail, "key derivation")
    }

    // spec: kdbx.hardening/AC2
    @Test
    fun aWipedKeyNeitherReadsNorWrites() {
        val wiped = key().also(DatabaseKey::wipe)
        assertFailsWith<WipedKeyException> { KdbxReader.open(fixture(), wiped) }
        assertFailsWith<WipedKeyException> { KdbxReader.openForEditing(fixture(), wiped) }

        val editable = KdbxReader.openForEditing(fixture(), key())
        assertFailsWith<WipedKeyException> { editable.write(wiped) }
        val written = editable.write().file
        editable.wipe()
        assertFailsWith<WipedKeyException>("the derived key that opened the file, wiped with it") { editable.write() }
        assertFailsWith<WipedKeyException> { editable.readBack(written) }

        val abandoned = key().also(DatabaseKey::abandon)
        assertFailsWith<WipedKeyException>("given up: never used again") { KdbxReader.open(fixture(), abandoned) }
        assertFailsWith<WipedKeyException> { abandoned.copy() }
    }

    // AC6: the key derivation's parameters

    private fun argon2File(parameters: ByteArray) = HostileFiles.withKdfParameters(fixture("kdbx4-aes-argon2d"), parameters)

    private fun refused(file: ByteArray): String = assertFailsWith<KdbxException.UnsupportedKdf> { KdbxReader.format(file) }.kdf

    // spec: kdbx.hardening/AC6
    @Test
    fun argon2OutsideKeePassLimitsIsRefusedAsUnsupportedNamingTheParameter() {
        assertContains(refused(argon2File(HostileFiles.argon2(version = 0x10))), "Argon2 version 0x10")
        assertContains(refused(argon2File(HostileFiles.argon2(iterations = 0))), "Argon2 with 0 iterations")
        assertContains(refused(argon2File(HostileFiles.argon2(iterations = 1L shl 32))), "Argon2 with 4294967296 iterations")
        assertContains(refused(argon2File(HostileFiles.argon2(iterations = -1))), "Argon2 with 18446744073709551615 iterations")
        assertContains(refused(argon2File(HostileFiles.argon2(lanes = 0))), "Argon2 with 0 lanes")
        assertContains(refused(argon2File(HostileFiles.argon2(lanes = 1L shl 24, memoryBytes = 1L shl 30))), "Argon2 with 16777216 lanes")
        assertContains(refused(argon2File(HostileFiles.argon2(memoryBytes = 4L shl 40))), "Argon2 memory of 4 TiB")
        assertContains(refused(argon2File(HostileFiles.argon2(memoryBytes = 2L shl 30))), "Argon2 memory of 2 GiB")
        assertContains(refused(argon2File(HostileFiles.argon2(memoryBytes = 64L * 1024 + 1))), "not whole KiB")
        assertContains(refused(argon2File(HostileFiles.argon2(memoryBytes = 8L shl 10, lanes = 2))), "8 KiB for 2 lanes")
    }

    // spec: kdbx.hardening/AC6
    @Test
    fun argon2AndAesKdfWithinTheLimitsAreRead() {
        KdbxReader.format(argon2File(HostileFiles.argon2(iterations = (1L shl 32) - 1, memoryBytes = (2L shl 30) - 1024, lanes = 262_143)))
        KdbxReader.format(argon2File(HostileFiles.argon2(memoryBytes = 16L shl 10, lanes = 2)))
        KdbxReader.format(HostileFiles.withKdfParameters(fixture(), HostileFiles.aesKdf(rounds = -1)))
        KdbxReader.format(HostileFiles.withKdfParameters(fixture(), HostileFiles.aesKdf(rounds = 1)))
    }

    // spec: kdbx.hardening/AC6
    @Test
    fun aesKdfWithNoRoundsIsRefused() {
        assertContains(refused(HostileFiles.withKdfParameters(fixture(), HostileFiles.aesKdf(rounds = 0))), "AES-KDF with no rounds")
    }

    // spec: kdbx.hardening/AC6
    @Test
    fun aCostlyKeyDerivationIsNeverReportedAsAWrongPasswordOrAnUnexpectedError() {
        // M3: the file swapped in with 4 TiB, its HMAC left as it was. Refused before anything is allocated.
        val huge = argon2File(HostileFiles.argon2(memoryBytes = 4L shl 40))
        assertIs<KdbxException.UnsupportedKdf>(assertFailsWith<KdbxException> { KdbxReader.open(huge, key()) })
        assertIs<KdbxException.UnsupportedKdf>(assertFailsWith<KdbxException> { KdbxReader.openForEditing(huge, key()) })
    }

    // spec: kdbx.hardening/AC6
    @Test
    fun argon2MemoryWithinKeePassLimitsButMoreThanThePhoneGivesIsRefusedNotRunOutOf() {
        AppMemory.most = { 256L shl 20 }
        val file = argon2File(HostileFiles.argon2(memoryBytes = 1L shl 30))

        val refusal = assertFailsWith<KdbxException.UnsupportedKdf> { KdbxReader.open(file, key()) }

        assertEquals("Argon2 memory of 1 GiB, more than the 256 MiB this phone gives Keething", refusal.kdf)
    }

    // spec: kdbx.hardening/AC6
    @Test
    fun aRunningKeyDerivationStopsWhenItsKeyIsGivenUp() {
        val endless = listOf(
            "AES-KDF" to HostileFiles.withKdfParameters(fixture(), HostileFiles.aesKdf(rounds = -1)),
            "Argon2, one lane" to argon2File(HostileFiles.argon2(iterations = (1L shl 32) - 1, memoryBytes = 1L shl 20)),
            "Argon2, four lanes" to argon2File(HostileFiles.argon2(iterations = (1L shl 32) - 1, memoryBytes = 1L shl 20, lanes = 4)),
        )
        val pool = Executors.newSingleThreadExecutor()
        try {
            for ((what, file) in endless) {
                val key = key()
                val open = pool.submit<Throwable?> { runCatching { KdbxReader.open(file, key) }.exceptionOrNull() }
                Thread.sleep(300)
                val given = System.nanoTime()
                key.abandon()
                val failure = open.get(5, TimeUnit.SECONDS)
                val stoppedIn = (System.nanoTime() - given) / 1_000_000
                assertIs<WipedKeyException>(failure, what)
                assertTrue(stoppedIn < 500, "$what stopped ${stoppedIn} ms after it was given up")
                key.wipe()
            }
        } finally {
            pool.shutdownNow()
        }
    }

    // AC7: a value's characters

    private val awkward = "pa\u0001ss\u001Bword\u000B\u000C\u0000￾￿\t\n\r end"

    // spec: kdbx.hardening/AC7
    @Test
    fun aProtectedValueWithAnyCharacterOpensAndIsWrittenBackAsItWas() {
        val key = key()
        val editable = KdbxReader.openForEditing(fixture(), key)
        val id = editable.document.entries.first().uuid
        val custom = CustomField("PIN", FieldValue.Protected(Secret.of(awkward)))
        editable.apply(VaultEdit.Update(id, null, EntryChanges(password = Secret.of(awkward), customFields = listOf(custom))), now)
        val once = editable.write().file

        // Opened, then saved again by another edit: still every character.
        val again = KdbxReader.openForEditing(once, key)
        again.apply(VaultEdit.Update(id, null, EntryChanges(title = "Renamed")), now)
        val twice = again.write().file

        for (file in listOf(once, twice)) {
            val entry = KdbxReader.open(file, key).document.entries.single { it.uuid == id }
            assertEquals(awkward, assertIs<FieldValue.Protected>(entry.field("Password")).secret.reveal())
            assertEquals(awkward, assertIs<FieldValue.Protected>(entry.field("PIN")).secret.reveal())
        }
    }

    // spec: kdbx.hardening/AC7
    @Test
    fun aProtectedValueKeePassXcStoredWithControlCharactersOpens() {
        if (!KeePassXcCli.isInstalled) return
        // M2's own recipe: keepassxc-cli edit -p, the new password on standard input.
        val database = File(folder, "control.kdbx").apply { writeBytes(fixture()) }
        val title = KdbxReader.open(fixture(), key()).document.entries.first().field("Title")!!.let { (it as FieldValue.Plain).text }
        val stored = "pa\u0001ss\u001Bword"
        val edit = KeePassXcCli.run("edit", "-p", database.path, title, input = "${Fixtures.PASSWORD}\n$stored\n$stored\n")
        assertEquals(0, edit.code, edit.err)

        val entry = KdbxReader.open(database.readBytes(), key()).document.entries.single { it.field("Title") == FieldValue.Plain(title) }

        assertEquals(stored, assertIs<FieldValue.Protected>(entry.field("Password")).secret.reveal())
    }

    // spec: kdbx.hardening/AC7
    @Test
    fun textInAPlainValueThatLooksLikeMarkupOrAnAttributeStaysThatText() {
        // L1: written unescaped, as KeePass 2.x, KeeWeb, KeePassDX and MacPass write a `"`, in compact and indented XML.
        val notes = "x Protected=\"True\" y <b>&amp;</b> ProtectInMemory=\"True\""
        for (gap in listOf("", "\n\t\t\t", "\r\n\t\t", "\n\t")) {
            val xml = "<KeePassFile><Root><Group><Entry><String><Key>Notes</Key><Value>x Protected=\"True\" y &lt;b&gt;&amp;amp;&lt;/b&gt; " +
                "ProtectInMemory=\"True\"</Value>$gap</String></Entry></Group></Root></KeePassFile>"
            val root = XmlTree.parse(xml.encodeToByteArray())
            val value = root.first("Root")!!.first("Group")!!.first("Entry")!!.first("String")!!.first("Value")!!
            assertEquals(notes, value.text, "gap ${gap.length}")
            assertNull(value.secret)
        }

        val key = key()
        val editable = KdbxReader.openForEditing(fixture(), key)
        val id = editable.document.entries.first().uuid
        editable.apply(VaultEdit.Update(id, null, EntryChanges(notes = notes, notesProtected = false)), now)
        val written = editable.write().file
        val again = KdbxReader.openForEditing(written, key)
        again.apply(VaultEdit.Update(id, null, EntryChanges(title = "Renamed")), now)

        val entry = KdbxReader.open(again.write().file, key).document.entries.single { it.uuid == id }
        assertEquals(FieldValue.Plain(notes), entry.field("Notes"))
    }

    // spec: kdbx.hardening/AC7
    @Test
    fun charactersXmlCannotHoldAreLeftOutOfAPlainValueAndTheFileIsWrittenAllTheSame() {
        // L6: they would make the XML invalid; KeePass 2.x and KeePassXC leave them out on write.
        val key = key()
        val editable = KdbxReader.openForEditing(fixture(), key)
        val id = editable.document.entries.first().uuid
        val changes = EntryChanges(
            title = "Ti\u000Btle\u0001",
            username = "u\u0000ser",
            url = "https://x.example/￾",
            notes = "line\u000Cbreak\ttab\nnew\rline",
            notesProtected = false,
            customFields = listOf(CustomField.plain("No\u001Fte", "pla\u0007in￿")),
            tags = listOf("t\u0002ag"),
        )
        editable.apply(VaultEdit.Update(id, null, changes), now)

        val entry = KdbxReader.open(editable.write().file, key).document.entries.single { it.uuid == id }

        assertEquals(FieldValue.Plain("Title"), entry.field("Title"))
        assertEquals(FieldValue.Plain("user"), entry.field("UserName"))
        assertEquals(FieldValue.Plain("https://x.example/"), entry.field("URL"))
        assertEquals(FieldValue.Plain("linebreak\ttab\nnew\rline"), entry.field("Notes"), "tab, LF and CR stay")
        assertEquals(FieldValue.Plain("plain"), entry.field("Note"))
        assertEquals("tag", entry.tags)
    }

    // AC8: nothing inside the encrypted part crashes the app

    // spec: kdbx.hardening/AC8
    @Test
    fun aTimeADateCannotHoldReadsAsNoTimeAndIsWrittenBackAsItWas() {
        // M6: Int64.min seconds; and a time past the year 9999, base64 and ISO 8601 (which KeePass's schema refuses).
        SchemaCheck.allowViolations()
        for (time in listOf("AAAAAAAAAIA=", "////////f/8=", "+12026-01-01T00:00:00Z", "0000-12-31T23:59:59Z")) {
            val key = key()
            val (file, id) = HostileFiles.withEntryTime(fixture(), key, time)

            val entry = KdbxReader.open(file, key).document.entries.single { it.uuid == id }
            assertNull(entry.lastModified, time)

            // Another entry edited and saved: the odd time goes back as it was.
            val editable = KdbxReader.openForEditing(file, key)
            val other = editable.document.entries.first { it.uuid != id }.uuid
            editable.apply(VaultEdit.Update(other, null, EntryChanges(notes = "edited")), now)
            val written = KdbxReader.openForEditing(editable.write().file, key)
            assertEquals(time, EntryLifecycle.locate(id, written.root)!!.entry.first("Times")!!.first("LastModificationTime")!!.text, time)
        }
    }

    // spec: kdbx.hardening/AC8
    @Test
    fun nestingDeeperThanTheAppWritesIsRefusedWhenReadAsDamaged() {
        val key = key()
        val (deepest, _) = HostileFiles.nested(fixture(), key, XmlTree.MAX_DEPTH)
        KdbxReader.open(deepest, key)
        KdbxReader.openForEditing(deepest, key).wipe()

        val (tooDeep, _) = HostileFiles.nested(fixture(), key, XmlTree.MAX_DEPTH + 1)

        for (read in listOf<() -> Any>({ KdbxReader.open(tooDeep, key) }, { KdbxReader.openForEditing(tooDeep, key) })) {
            val refusal = assertFailsWith<KdbxException.Corrupt> { read() }
            assertEquals("XML nested deeper than ${XmlTree.MAX_DEPTH} levels", refusal.detail)
        }
    }
}
