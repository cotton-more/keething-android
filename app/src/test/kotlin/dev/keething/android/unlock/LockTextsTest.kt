package dev.keething.android.unlock

import dev.keething.android.ui.english
import dev.keething.vault.opening.OpenFailure
import dev.keething.vault.opening.OpenFailure.Unsupported.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the lock screen and the chooser say about a database that did not open (SPEC §6.2). */
class LockTextsTest {
    // spec: app.first-start/AC3
    @Test
    fun aDatabaseNotReadIsNamedWithWhatToDoNeverAsAWrongPasswordOrABareCannotBeOpened() {
        val twofish = OpenFailure.Unsupported("cipher Twofish", Kind.TWOFISH)
        assertEquals(
            "This database is encrypted with Twofish, which Keething does not read. In KeePassXC (Database › Database " +
                "Security › Encryption Settings) or KeePass, choose AES-256 or ChaCha20 and save; then it opens here.",
            twofish.describe("db.kdbx").english(),
        )
        assertEquals("encrypted with Twofish", twofish.named().english())
        assertEquals(
            "This database is in an old KeePass format (KDBX 2.0), which Keething does not read. Save it again in " +
                "KeePass or KeePassXC, and it opens here.",
            OpenFailure.Unsupported("KDBX 2.0", Kind.OLD_FORMAT).describe("db.kdbx").english(),
        )
        val keepass1 = OpenFailure.Unsupported("KeePass 1.x (.kdb)", Kind.KEEPASS1)
        assertEquals(
            "This is a KeePass 1.x database (.kdb), a format Keething does not read. Import it in KeePassXC (Database › " +
                "Import) or KeePass 2 (File › Import) and save it there: the new .kdbx file opens here.",
            keepass1.describe("db.kdbx").english(),
        )
        assertEquals("KeePass 1.x (.kdb)", keepass1.named().english())
        assertEquals(
            "This database is in a newer KeePass format (KDBX 5.0) than this Keething reads. An update of Keething may open it.",
            OpenFailure.Unsupported("KDBX 5.0", Kind.NEWER_FORMAT).describe("db.kdbx").english(),
        )
        assertEquals(
            "This database's key derivation is one Keething does not run: Argon2 with secret.",
            OpenFailure.Unsupported("key derivation Argon2 with secret", Kind.KEY_DERIVATION).describe("db.kdbx").english(),
        )
        assertEquals("unknown cipher 0011", OpenFailure.Unsupported("cipher 0011", Kind.CIPHER).named().english())
        for (kind in Kind.entries) {
            val text = OpenFailure.Unsupported("x", kind).describe("db.kdbx").english()
            assertFalse(text.contains("password", ignoreCase = true) || text.contains("cannot be opened") || text.contains("damaged"), "$kind: $text")
        }
    }

    // spec: app.first-start/AC4
    @Test
    fun tooLittleMemoryForTheKeyDerivationSaysWhatToLower() {
        val memory = OpenFailure.Unsupported("key derivation Argon2 memory of 1 GiB, more than the 512 MiB this phone gives Keething", Kind.MEMORY)
        assertEquals(
            "This phone does not give Keething enough memory for this database's key derivation (Argon2 memory of 1 GiB, " +
                "more than the 512 MiB this phone gives Keething). Lower Argon2's memory in the database's settings on the " +
                "computer (in KeePassXC: Database › Database Security), then it opens here.",
            memory.describe("db.kdbx").english(),
        )
    }

    // spec: app.first-start/AC5
    @Test
    fun aHardwareKeyIsNamedFromTheSecondWrongKeyInARowOnly() {
        assertEquals("Wrong password — or this database needs a key file.", OpenFailure.WrongPassword.describe("db.kdbx", wrongInARow = 1).english())
        val second = OpenFailure.WrongPassword.describe("db.kdbx", wrongInARow = 2).english()
        assertEquals(
            "Wrong password — or this database needs a key file. A database that also needs a YubiKey (or another " +
                "hardware key) cannot be opened in Keething.",
            second,
        )
        assertTrue(OpenFailure.WrongPasswordOrKeyFile.describe("db.kdbx", wrongInARow = 3).english().contains("YubiKey"))
        assertTrue(OpenFailure.WrongKeyFile.describe("db.kdbx", wrongInARow = 2).english().contains("YubiKey"))
        assertFalse(OpenFailure.Missing.describe("db.kdbx", wrongInARow = 2).english().contains("YubiKey"), "only of a wrong key")
    }
}
