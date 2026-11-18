package dev.keething.vault.opening

import dev.keething.domain.secret.Secret
import dev.keething.kdbx.editing.HostileFiles
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.vault.session.FakeStore
import dev.keething.vault.session.fixtureBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** A database the app does not read is refused by what it is, never as a wrong password or a damaged file. */
class UnsupportedDatabasesTest {
    private fun key() = DatabaseKey.of(Secret.of("pw"))

    private fun refusal(file: ByteArray): OpenFailure.Unsupported =
        assertIs<OpenFailure.Unsupported>(assertIs<OpenResult.Failed>(VaultOpener().open(FakeStore(file), key())).failure)

    /** [file] with its cipher's UUID (AES-256's) replaced by [cipher]'s. */
    private fun withCipher(file: ByteArray, cipher: String): ByteArray {
        val aes = hex(AES_256)
        val at = (0..file.size - aes.size).first { i -> aes.indices.all { file[i + it] == aes[it] } }
        return file.copyOf().also { hex(cipher).copyInto(it, at) }
    }

    private fun hex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // spec: app.first-start/AC3
    @Test
    fun twofishOldAndNewerFormatsAndUnknownPartsAreRefusedByWhatTheyAre() {
        assertEquals(
            OpenFailure.Unsupported("cipher Twofish", OpenFailure.Unsupported.Kind.TWOFISH),
            refusal(withCipher(fixtureBytes("kdbx4-aes-argon2d"), TWOFISH)),
        )
        assertEquals(OpenFailure.Unsupported.Kind.TWOFISH, refusal(withCipher(fixtureBytes("kdbx3"), TWOFISH)).kind, "KDBX 3.1")

        val unknownCipher = refusal(withCipher(fixtureBytes("kdbx4-aes-argon2d"), "00112233445566778899aabbccddeeff"))
        assertEquals(OpenFailure.Unsupported.Kind.CIPHER, unknownCipher.kind)

        val kdbx2 = fixtureBytes("kdbx3").also { it[8] = 0; it[10] = 2 }
        assertEquals(OpenFailure.Unsupported("KDBX 2.0", OpenFailure.Unsupported.Kind.OLD_FORMAT), refusal(kdbx2))
        val kdbx5 = fixtureBytes("kdbx4-aes-argon2d").also { it[8] = 0; it[10] = 5 }
        assertEquals(OpenFailure.Unsupported("KDBX 5.0", OpenFailure.Unsupported.Kind.NEWER_FORMAT), refusal(kdbx5))
        val keepass1 = fixtureBytes("kdbx3").also { it[4] = 0x65 }
        assertEquals(OpenFailure.Unsupported("KeePass 1.x (.kdb)", OpenFailure.Unsupported.Kind.KEEPASS1), refusal(keepass1))

        assertEquals(OpenFailure.Unsupported.Kind.NOT_KEEPASS, refusal("<KeePassFile/>".encodeToByteArray()).kind)

        val outsideLimits = HostileFiles.withKdfParameters(fixtureBytes("kdbx4-aes-argon2d"), HostileFiles.argon2(memoryBytes = 4L shl 40))
        assertEquals(OpenFailure.Unsupported.Kind.KEY_DERIVATION, refusal(outsideLimits).kind)
    }

    private companion object {
        const val AES_256 = "31c1f2e6bf714350be5805216afc5aff"
        const val TWOFISH = "ad68f29f576f4bb9a36ad47af965346c"
    }
}
