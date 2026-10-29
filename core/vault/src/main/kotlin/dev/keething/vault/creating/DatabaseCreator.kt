package dev.keething.vault.creating

import dev.keething.domain.encryption.MeasuredEncryption
import dev.keething.domain.secret.Secret
import dev.keething.domain.storage.KeyFileRead
import dev.keething.domain.storage.KeyFileSource
import dev.keething.domain.storage.NewFile
import dev.keething.domain.storage.NewFilePlace
import dev.keething.domain.vault.FieldValue
import dev.keething.kdbx.creating.NewDatabase
import dev.keething.kdbx.editing.EditException
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.kdbx.key.KeyFile
import dev.keething.kdbx.reader.KdbxDatabase
import dev.keething.kdbx.reader.KdbxException
import dev.keething.kdbx.reader.KdbxFormat
import dev.keething.kdbx.reader.KdbxReader
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.time.Instant

/** Why a new database or key file was not made (the Mac's `KDBXFileCreator.Failure`). Nothing is left behind. */
public sealed class CreateFailure(message: String) : Exception(message) {
    /** Something is at the path already ([name]): a database or a key file is never replaced. */
    public class Exists(public val name: String) : CreateFailure("there is already a $name")

    /** The folder to save in is not there. */
    public class FolderNotFound : CreateFailure("the folder is not there")

    public class WriteFailed(public val why: String) : CreateFailure("not written: $why")

    /** The phone has not enough memory for the key derivation (Argon2's memory as chosen). */
    public class NotEnoughMemory : CreateFailure("not enough memory for the key derivation")

    /** The new file did not read back as made. */
    public class VerificationFailed(public val why: String) : CreateFailure("did not check out: $why")

    /** A format or KDF parameters that are not written ([what]: Twofish; Argon2 parameters KeePass would not run). */
    public class Unsupported(public val what: String) : CreateFailure("not written: $what")
}

/**
 * The key a new database was made with, its derived key included: the first unlock opens the database with it and
 * derives nothing (the Mac's `DerivedKeyStore.take`). [wipe] when it is not used.
 */
public class DerivedKey internal constructor(internal val key: DatabaseKey) {
    public fun wipe(): Unit = key.wipe()
}

/**
 * Makes a new database file, and a new key file for one (keething-spec kdbx.new-file, kdbx.new-key-file; the Mac's
 * `KDBXFileCreator`). The database is made in memory with the key derived once, read back by the app's own reader
 * (its name, its one root group, no entries, no attachments, the format asked for), and only then put at its place
 * by [NewFile], which never replaces a file there. Blocks for as long as the key derivation takes: call it off the
 * main thread.
 */
public class DatabaseCreator(private val now: () -> Instant) {
    /**
     * Writes an empty database to [target]. [name] is its `Meta/DatabaseName` (null or blank: none; the root group then
     * takes the file name); [kdf] as measured ([dev.keething.kdbx.creating.KdfCalibration]). [key] keeps the key derived
     * for the file, so the first unlock with it derives nothing; it stays the caller's. Throws a [CreateFailure].
     */
    public fun create(target: NewFile, name: String?, key: DatabaseKey, cipher: KdbxFormat.Cipher, kdf: KdbxFormat.Kdf) {
        checkFree(target)
        val file = try {
            NewDatabase.file(name, target.fileName, cipher, kdf, key, now())
        } catch (failure: KdbxException.UnsupportedCipher) {
            throw CreateFailure.Unsupported(failure.cipher)
        } catch (failure: KdbxException.UnsupportedKdf) {
            throw CreateFailure.Unsupported(failure.kdf)
        } catch (failure: EditException.BreaksFormat) {
            throw CreateFailure.VerificationFailed("KeePass's format does not allow it: ${failure.what}")
        } catch (_: OutOfMemoryError) {
            throw CreateFailure.NotEnoughMemory()
        }
        verify(file, key, name, target.fileName, KdbxFormat("4.0", cipher, kdf, compressed = true))
        place(target) { target.create(file) }
    }

    /** [create] with what was measured ([EncryptionMeasurement.measure]): its cipher, and its KDF with the units measured. */
    public fun create(target: NewFile, name: String?, key: DatabaseKey, measured: MeasuredEncryption) {
        create(target, name, key, EncryptionMeasurement.cipher(measured.encryption.cipher), EncryptionMeasurement.kdf(measured))
    }

    /**
     * [create] with the key made of [password] and [keyFile]'s key (at least one; an empty password is a password;
     * both stay the caller's to wipe). The key comes back derived, for the first unlock; wiped when this throws.
     */
    public fun create(target: NewFile, name: String?, password: Secret?, keyFile: Secret?, measured: MeasuredEncryption): DerivedKey {
        val key = DatabaseKey.of(password, keyFile)
        try {
            create(target, name, key, measured)
        } catch (failure: Throwable) {
            key.wipe()
            throw failure
        }
        return DerivedKey(key)
    }

    /**
     * Writes a new key file to [target] (kdbx.new-key-file): XML 2.0 as KeePassXC writes it, read-only for its owner
     * where the storage has permissions, never over a file there; read back through [readBack] (the same file) before
     * its key is returned. The caller wipes the key. Throws a [CreateFailure].
     */
    public fun createKeyFile(target: NewFile, readBack: KeyFileSource): Secret {
        checkFree(target)
        val key = KeyFile.newKey()
        val contents = KeyFile.contents(key)
        try {
            place(target) { target.create(contents, readOnly = true) }
        } catch (failure: CreateFailure) {
            key.wipe()
            throw failure
        } finally {
            contents.fill(0)
        }
        // Anything that keeps it from reading back as written (gone, unreadable, not a key file) counts the same.
        val reread = try {
            (readBack.read() as? KeyFileRead.Found)?.let { found ->
                try {
                    KeyFile.key(found.bytes)
                } finally {
                    found.bytes.fill(0)
                }
            }
        } catch (_: Exception) {
            null
        }
        val same = reread?.hasSameBytes(key) == true
        reread?.wipe()
        if (!same) {
            runCatching { target.removeCreated() }
            key.wipe()
            throw CreateFailure.WriteFailed("the key file did not read back")
        }
        return key
    }

    /** Nothing at the path, and its folder there: said before anything is derived or written. */
    private fun checkFree(target: NewFile) {
        val place = try {
            target.check()
        } catch (failure: IOException) {
            throw CreateFailure.WriteFailed(failure.message ?: "the folder could not be read")
        }
        when (place) {
            NewFilePlace.FREE -> Unit
            NewFilePlace.TAKEN -> throw CreateFailure.Exists(target.fileName)
            NewFilePlace.NO_FOLDER -> throw CreateFailure.FolderNotFound()
        }
    }

    private fun place(target: NewFile, create: () -> Unit) {
        try {
            create()
        } catch (_: FileAlreadyExistsException) {
            throw CreateFailure.Exists(target.fileName)
        } catch (_: NoSuchFileException) {
            throw CreateFailure.FolderNotFound()
        } catch (failure: IOException) {
            throw CreateFailure.WriteFailed(failure.message ?: failure.javaClass.simpleName)
        }
    }

    /** The written file opens with its key and is what was asked for (kdbx.new-file AC7). */
    private fun verify(file: ByteArray, key: DatabaseKey, name: String?, fileName: String, format: KdbxFormat) {
        val database: KdbxDatabase = try {
            KdbxReader.open(file, key)
        } catch (_: KdbxException) {
            throw CreateFailure.VerificationFailed("the new file does not open")
        }
        val document = database.document
        try {
            if (database.format != format) throw CreateFailure.VerificationFailed("the new file's format differs")
            val asMade = document.meta.databaseName == NewDatabase.databaseName(name) &&
                document.groups.map { it.name } == listOf(NewDatabase.rootGroupName(name, fileName)) &&
                document.entries.isEmpty() && database.binaries.isEmpty()
            if (!asMade) throw CreateFailure.VerificationFailed("the new file's contents differ")
        } finally {
            document.entries.forEach { entry -> entry.fields.forEach { (it.value as? FieldValue.Protected)?.secret?.wipe() } }
            database.binaries.forEach(Secret::wipe)
        }
    }
}
