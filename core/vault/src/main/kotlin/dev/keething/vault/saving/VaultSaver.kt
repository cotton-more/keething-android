package dev.keething.vault.saving

import dev.keething.domain.secret.Secret
import dev.keething.domain.storage.DatabaseBackups
import dev.keething.domain.storage.DatabaseRead
import dev.keething.domain.storage.DatabaseStore
import dev.keething.domain.vault.Vault
import dev.keething.domain.vault.VaultEdit
import dev.keething.kdbx.editing.EditException
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.kdbx.reader.KdbxDatabase
import dev.keething.kdbx.reader.KdbxException
import dev.keething.kdbx.reader.KdbxReader
import dev.keething.kdbx.xml.KdbxDocument
import dev.keething.vault.opening.OpenFailure
import dev.keething.vault.opening.VaultAssembler
import dev.keething.vault.opening.toOpenFailure
import dev.keething.vault.opening.toOpenedVault
import java.io.IOException
import java.time.Duration
import java.time.Instant

/**
 * Saves one change into the database file (SPEC §3.3; port of the Mac `KDBXFileSaver`). The file is read again
 * right before writing, so changes made elsewhere since the unlock (KeePassXC, a synced device) are kept; only
 * when that very entry changed meanwhile is the save refused. The new file is read back and checked before it
 * replaces the old one ([SaveCheck]), and the old one is backed up. The key's derived key does all the work: a
 * save derives nothing. Blocks: call it off the main thread.
 */
internal class VaultSaver(private val store: DatabaseStore, private val backups: DatabaseBackups) {
    fun save(edit: VaultEdit, key: DatabaseKey, now: Instant): SaveResult = try {
        saveOrRefuse(edit, key, now)
    } catch (refused: Refused) {
        SaveResult.Failed(refused.failure)
    } catch (failure: IOException) {
        SaveResult.Failed(SaveFailure.WriteFailed(failure.message ?: failure.javaClass.simpleName))
    } catch (_: OutOfMemoryError) {
        SaveResult.Failed(SaveFailure.OutOfMemory)
    }

    private fun saveOrRefuse(edit: VaultEdit, key: DatabaseKey, now: Instant): SaveResult {
        repeat(ATTEMPTS) {
            val original = read()
            val made = makeFile(original, edit, key, now)
            // The file must still be what was read; otherwise read it again and redo the edit on top.
            val current = store.read()
            if (current !is DatabaseRead.Found || !current.bytes.contentEquals(original)) {
                made.discard()
                return@repeat
            }
            try {
                backups.keep(original, store.fileName, now)
                store.replace(made.file)
            } catch (failure: Exception) {
                made.discard()
                throw Refused(SaveFailure.WriteFailed(failure.message ?: failure.javaClass.simpleName))
            }
            val version = store.version()
            if (version == null) {
                made.discard()
                throw Refused(SaveFailure.WriteFailed("the new file is gone"))
            }
            return SaveResult.Saved(made.reread.toOpenedVault(version, made.vault))
        }
        throw Refused(SaveFailure.FileKeepsChanging)
    }

    private fun read(): ByteArray = when (val read = store.read()) {
        is DatabaseRead.Found -> read.bytes
        DatabaseRead.Missing -> throw Refused(SaveFailure.CannotOpen(OpenFailure.Missing))
        DatabaseRead.AccessDenied -> throw Refused(SaveFailure.CannotOpen(OpenFailure.AccessDenied))
    }

    /** The new file, read back and checked; [vault] is what an unlock would show of it. */
    private class Made(val file: ByteArray, val reread: KdbxDatabase, val vault: Vault) {
        fun discard() {
            vault.wipe()
            reread.binaries.forEach(Secret::wipe)
        }
    }

    /** Opens [original], applies the change and writes the new file; then reads that back and checks it. */
    private fun makeFile(original: ByteArray, edit: VaultEdit, key: DatabaseKey, now: Instant): Made {
        val editable = try {
            KdbxReader.openForEditing(original, key)
        } catch (failure: KdbxException) {
            throw Refused(SaveFailure.CannotOpen(failure.toOpenFailure()))
        }
        var before: Vault? = null
        try {
            checkAsLoaded(editable.document, edit)
            before = VaultAssembler.assemble(editable.document, editable.binaries.map(Secret::size))
            val applied = try {
                editable.apply(edit, now)
            } catch (failure: EditException) {
                throw Refused(failure.toSaveFailure())
            }
            // Attachments nothing refers to any more are dropped; every reference must still mean the same bytes.
            // Nothing KeePass's schema does not allow may be added (SPEC §3.3).
            val written = try {
                editable.write(key)
            } catch (failure: EditException.BreaksFormat) {
                throw Refused(SaveFailure.BreaksFormat(failure.what))
            } catch (failure: EditException) {
                throw Refused(SaveFailure.VerificationFailed(failure.message ?: "attachments"))
            }

            // Read back what was written, as an unlock would. With the kept key this proves the whole file but the
            // derivation, which is the same as for the file just opened (the same key, KDF parameters and salt).
            val reread = try {
                KdbxReader.open(written.file, key)
            } catch (_: KdbxException) {
                throw Refused(SaveFailure.VerificationFailed("the new file does not open"))
            }
            var after: Vault? = null
            try {
                after = VaultAssembler.assemble(reread.document, reread.binaries.map(Secret::size))
                try {
                    SaveCheck.verify(before, after, edit, applied, now)
                } catch (failure: SaveCheck.Failed) {
                    throw Refused(SaveFailure.VerificationFailed(failure.detail))
                }
                if (reread.binaries != written.binaries) throw Refused(SaveFailure.VerificationFailed("attachments differ"))
                return Made(written.file, reread, after)
            } catch (refused: Refused) {
                after?.wipe()
                reread.binaries.forEach(Secret::wipe)
                throw refused
            }
        } finally {
            before?.wipe()
            editable.wipe()
        }
    }

    /** The entry must be in the file as it was loaded: a different `LastModificationTime` means changed elsewhere. */
    private fun checkAsLoaded(document: KdbxDocument, edit: VaultEdit) {
        val existing = document.entries.firstOrNull { it.uuid == edit.entryId }
        val loaded = when (edit) {
            is VaultEdit.Create -> {
                if (existing != null) throw Refused(SaveFailure.VerificationFailed("the new entry's id is taken"))
                return
            }
            is VaultEdit.Update -> edit.loadedModified
            is VaultEdit.MoveToRecycleBin -> edit.loadedModified
            is VaultEdit.Restore -> edit.loadedModified
        }
        if (existing == null) throw Refused(SaveFailure.EntryNotFound)
        val inFile = existing.lastModified
        if (loaded != null && inFile != null && Duration.between(loaded, inFile).abs() >= Duration.ofSeconds(1)) {
            throw Refused(SaveFailure.ChangedElsewhere)
        }
    }

    private fun EditException.toSaveFailure(): SaveFailure = when (this) {
        is EditException.EntryNotFound -> SaveFailure.EntryNotFound
        is EditException.GroupNotFound -> SaveFailure.GroupNotFound
        is EditException.RecycleBinDisabled -> SaveFailure.RecycleBinDisabled
        is EditException.AlreadyInRecycleBin, is EditException.NotInRecycleBin -> SaveFailure.WrongPlace
        is EditException.EntryExists -> SaveFailure.VerificationFailed("the new entry's id is taken")
        is EditException.MalformedLinks -> SaveFailure.VerificationFailed("the recycle bin's groups point to each other in a circle")
        is EditException.Malformed -> SaveFailure.VerificationFailed(message ?: "malformed")
        is EditException.BreaksFormat -> SaveFailure.BreaksFormat(what)
    }

    /** A save that wrote nothing, and why. */
    private class Refused(val failure: SaveFailure) : Exception()

    private companion object {
        /** The file read once more when it changed during the save, then the save gives up. */
        const val ATTEMPTS = 2
    }
}
