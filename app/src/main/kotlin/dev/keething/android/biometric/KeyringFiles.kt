package dev.keething.android.biometric

import dev.keething.domain.biometric.SeveralOpening
import dev.keething.domain.biometric.StoredPasswordOpening
import dev.keething.domain.secret.Secret
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.PrivateKey
import java.security.PublicKey
import javax.crypto.Cipher

/** The Keystore's key the fingerprint unlocks for one operation at a time; an interface so the files are tested on the JVM. */
internal interface FingerprintKey {
    /** Its public half, as a copy outside the Keystore; null when there is none. */
    fun publicKey(): PublicKey?

    /**
     * A cipher for its private half, ready for the prompt; null when there is none or a fingerprint change invalidated
     * it. Any other failure of the Keystore throws [IOException]: said, and nothing is given up for it.
     */
    fun decryptingCipher(): Cipher?

    /** A new key in place of any; its public half. */
    fun create(): PublicKey

    fun delete()
}

/**
 * Each database's sealed password and the [Keyring] in [folder] (SPEC §5): `keyring`, and `databases/<name>` per
 * database. What a prompt needs is found before it ([prepare]), what it opened after it ([open]); every failure says
 * whose stored password it costs: none ([IOException]: reported, kept), the one database's
 * ([StoredPasswordOpening.CopyInvalidated]) or all of them ([StoredPasswordOpening.KeyInvalidated]). Blocking file and
 * Keystore work, one call at a time.
 */
internal class KeyringFiles(private val folder: File, private val key: FingerprintKey) {
    private val keyringFile get() = File(folder, "keyring")
    private val databasesFolder get() = File(folder, "databases")

    /** Before the prompt: the cipher to show it for, or what comes of [database] without one. */
    sealed interface Prepared {
        class Ask internal constructor(val cipher: Cipher, internal val sealed: ByteArray, internal val keyring: Keyring) : Prepared

        class Answer(val opening: StoredPasswordOpening) : Prepared
    }

    /** Before one prompt for several databases (plan AD13): the cipher to show it for, or why none can open. */
    sealed interface PreparedKeyring {
        class Ask internal constructor(val cipher: Cipher, internal val keyring: Keyring) : PreparedKeyring

        class Answer(val opening: SeveralOpening) : PreparedKeyring
    }

    /**
     * The keyring opened after the fingerprint: each database's password, read in turn ([open]), then dropped. A cipher
     * per database: one that failed on another keyring's copy may refuse the next until it is reset (found by AD13's review).
     */
    class OpenKeyring internal constructor(private val privateKey: PrivateKey, private val files: KeyringFiles) {
        /** [database]'s stored password; throws [IOException] when its file cannot be read. */
        fun open(database: String): StoredPasswordOpening = files.openPassword(database, Keyring.opening(privateKey))
    }

    fun hasPassword(database: String): Boolean = File(databasesFolder, database).exists()

    fun save(database: String, password: Secret) {
        write(File(databasesFolder, database), SealedPassword.seal(password, keyringForSealing().publicKey))
    }

    fun prepare(database: String): Prepared {
        val sealed = File(databasesFolder, database).takeIf(File::exists)?.readBytes()
            ?: return Prepared.Answer(StoredPasswordOpening.NothingStored)
        val keyring = try {
            // No keyring: what was sealed for the one before can never open.
            readKeyring() ?: return Prepared.Answer(StoredPasswordOpening.CopyInvalidated)
        } catch (damaged: UnreadableSealException) {
            return Prepared.Answer(StoredPasswordOpening.KeyInvalidated)
        }
        val cipher = key.decryptingCipher() ?: return Prepared.Answer(StoredPasswordOpening.KeyInvalidated)
        // Made for a key since replaced (a fingerprint change, a keyring whose writing failed): nothing it holds opens.
        val current = key.publicKey()
        if (current == null || !keyring.isFor(current)) return Prepared.Answer(StoredPasswordOpening.KeyInvalidated)
        return Prepared.Ask(cipher, sealed, keyring)
    }

    /**
     * After the fingerprint: the password [ask] prepared. The Keystore refusing its own keyring is said and costs nothing
     * (it may be for a moment): [IOException].
     */
    fun open(ask: Prepared.Ask): StoredPasswordOpening {
        val privateKey = privateKey(ask.keyring, ask.cipher) ?: return StoredPasswordOpening.KeyInvalidated
        return opened(ask.sealed, Keyring.opening(privateKey))
    }

    /**
     * Before one prompt for several databases: the cipher to show it for. A fingerprint change, a keyring made for a
     * key since replaced, a damaged keyring or none at all: nothing stored can open, every database's is lost
     * ([SeveralOpening.KeyInvalidated]). The Keystore failing to give its key: [IOException].
     */
    fun prepareKeyring(): PreparedKeyring {
        val keyring = try {
            readKeyring()
        } catch (damaged: UnreadableSealException) {
            null
        } ?: return PreparedKeyring.Answer(SeveralOpening.KeyInvalidated)
        val cipher = key.decryptingCipher() ?: return PreparedKeyring.Answer(SeveralOpening.KeyInvalidated)
        val current = key.publicKey()
        if (current == null || !keyring.isFor(current)) return PreparedKeyring.Answer(SeveralOpening.KeyInvalidated)
        return PreparedKeyring.Ask(cipher, keyring)
    }

    /**
     * After the fingerprint: the keyring [ask] prepared, open for each database's password; null when its private key
     * does not open (every database's is lost). The Keystore refusing is said and costs nothing: [IOException].
     */
    fun openKeyring(ask: PreparedKeyring.Ask): OpenKeyring? = privateKey(ask.keyring, ask.cipher)?.let { OpenKeyring(it, this) }

    /** [database]'s password with the keyring's [opening] cipher. */
    private fun openPassword(database: String, opening: Cipher): StoredPasswordOpening {
        val sealed = File(databasesFolder, database).takeIf(File::exists)?.readBytes() ?: return StoredPasswordOpening.NothingStored
        return opened(sealed, opening)
    }

    /** The keyring's private key, opened with [cipher]; null when it does not open. */
    private fun privateKey(keyring: Keyring, cipher: Cipher): PrivateKey? = try {
        keyring.privateKey(cipher)
    } catch (refused: UnwrapRefusedException) {
        throw IOException("the Keystore did not open the keyring (${refused.cause?.javaClass?.simpleName})", refused) // not user text
    } catch (damaged: UnreadableSealException) {
        null
    }

    private fun opened(sealed: ByteArray, opening: Cipher): StoredPasswordOpening = try {
        StoredPasswordOpening.Opened(SealedPassword.open(sealed, opening))
    } catch (unreadable: UnreadableSealException) {
        // Sealed for another keyring, or changed: this database's alone can never open.
        StoredPasswordOpening.CopyInvalidated
    }

    /** True when there was one to delete. */
    fun delete(database: String): Boolean {
        val file = File(databasesFolder, database)
        if (!file.exists()) return false
        if (!file.delete()) throw IOException("could not delete a stored password") // not user text
        return true
    }

    /** The databases' first (a keyring of later could never open them), then the keyring, then the key. */
    fun deleteAll() {
        databasesFolder.listFiles()?.forEach { if (!it.delete()) throw IOException("could not delete a stored password") } // not user text
        if (keyringFile.exists() && !keyringFile.delete()) throw IOException("could not delete the keyring") // not user text
        key.delete()
    }

    /**
     * The keyring to seal a password for. None yet, a damaged one, or one made for a Keystore key since invalidated or
     * replaced gets a new one, for a new key when the old one is invalidated. The other databases' passwords sealed for
     * the old one stay until each is tried: then its own lock screen says the fingerprints changed.
     */
    private fun keyringForSealing(): Keyring {
        val existing = try {
            readKeyring()
        } catch (damaged: UnreadableSealException) {
            null
        }
        val current = key.publicKey()?.takeIf { key.decryptingCipher() != null }
        if (existing != null && current != null && existing.isFor(current)) return existing
        return Keyring.make(current ?: key.create()).also { write(keyringFile, it.encoded()) }
    }

    /** Null when there is none; throws [UnreadableSealException] when it is damaged. */
    private fun readKeyring(): Keyring? = keyringFile.takeIf(File::exists)?.readBytes()?.let(Keyring::read)

    /** Through a temporary file and a rename: a file is there whole or not at all. */
    private fun write(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(temporary).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        if (!temporary.renameTo(file)) {
            temporary.delete()
            throw IOException("could not write ${file.name}") // not user text
        }
    }
}
