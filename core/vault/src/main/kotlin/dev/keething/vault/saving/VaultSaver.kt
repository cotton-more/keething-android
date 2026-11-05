package dev.keething.vault.saving

import dev.keething.domain.secret.Secret
import dev.keething.domain.storage.DatabaseBackups
import dev.keething.domain.storage.DatabaseChangedException
import dev.keething.domain.storage.DatabaseRead
import dev.keething.domain.storage.DatabaseReplacedException
import dev.keething.domain.storage.DatabaseStore
import dev.keething.domain.vault.Vault
import dev.keething.domain.vault.VaultEdit
import dev.keething.domain.vault.VaultGroup
import dev.keething.kdbx.editing.EditException
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.kdbx.key.WipedKeyException
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
 * Saves one change into the database file (SPEC §3.3; port of the Mac `KDBXFileSaver`): an entry's or the groups'.
 * The file is read again right before writing, so changes made elsewhere since the unlock (KeePassXC, a synced
 * device) are kept; only when that very entry changed meanwhile, or the groups no longer allow the change (a group
 * gone, a name taken, a group not empty), is the save refused. The new file is read back and checked before it
 * replaces the old one ([SaveCheck]), and the old one is backed up. The derived key that opens the file read does
 * all the work: the new file is written and read back with it, and a save derives nothing (keething-spec
 * kdbx.hardening AC1). [stopped] is asked right before the backup and right before the replace: a save the lock
 * overtook stops there, the file and its backups as they were (AC3). A key wiped meanwhile fails it so too (AC2).
 * Blocks: call it off the main thread.
 */
internal class VaultSaver(private val store: DatabaseStore, private val backups: DatabaseBackups) {
    fun save(edit: VaultEdit, key: DatabaseKey, now: Instant, stopped: () -> Boolean = { false }): SaveResult =
        refusing { saveOrRefuse(stopped, make = { original -> makeFile(original, edit, key, now) }, now = now) }

    /**
     * Upgrades a KDBX 3.1 database to KDBX 4.0 (keething-spec kdbx.kdbx3 AC7, AC8): the 3.1 file is first kept beside
     * it as `<stem> (KDBX 3.1)<extension>` ([DatabaseStore.keepBeside]), then the database is written as KDBX 4.0, its
     * cipher, compression and key derivation as read, and read back: everything in it as before. Saved like any change
     * (backed up, replaced only while the file is what was read). A file that is KDBX 4 by now is
     * [SaveFailure.ChangedElsewhere]; a copy that cannot be kept, [SaveFailure.CopyNotKept], nothing written.
     */
    fun upgrade(key: DatabaseKey, now: Instant, stopped: () -> Boolean = { false }): SaveResult {
        // The 3.1 file kept: a second attempt (the file changed meanwhile) keeps it again only when it is another file.
        var kept: ByteArray? = null
        var keptAs: String? = null
        return refusing {
            val result = saveOrRefuse(stopped, make = { original -> upgraded(original, key) }, now = now) { original ->
                if (kept?.contentEquals(original) == true) return@saveOrRefuse
                keptAs = try {
                    store.keepBeside(original, copyName(store.fileName))
                } catch (failure: Exception) {
                    throw Refused(SaveFailure.CopyNotKept(failure.message ?: failure.javaClass.simpleName))
                }
                kept = original
            }
            if (result is SaveResult.Saved) SaveResult.Saved(result.opened, keptCopy = keptAs) else result
        }
    }

    private fun refusing(save: () -> SaveResult): SaveResult = try {
        save()
    } catch (refused: Refused) {
        SaveResult.Failed(refused.failure)
    } catch (_: WipedKeyException) {
        SaveResult.Failed(SaveFailure.Locked)
    } catch (failure: IOException) {
        SaveResult.Failed(SaveFailure.WriteFailed(failure.message ?: failure.javaClass.simpleName))
    } catch (_: OutOfMemoryError) {
        SaveResult.Failed(SaveFailure.OutOfMemory)
    }

    /** [beforeBackup]: what is done once the new file is made and the old one is still what was read. */
    private fun saveOrRefuse(
        stopped: () -> Boolean,
        make: (original: ByteArray) -> Made,
        now: Instant,
        beforeBackup: (original: ByteArray) -> Unit = {},
    ): SaveResult {
        repeat(ATTEMPTS) {
            val original = read()
            val made = make(original)
            // The file must still be what was read; otherwise read it again and redo the edit on top.
            val current = store.read()
            if (current !is DatabaseRead.Found || !current.bytes.contentEquals(original)) {
                made.discard()
                return@repeat
            }
            if (stopped()) {
                made.discard()
                throw Refused(SaveFailure.Locked)
            }
            try {
                beforeBackup(original)
            } catch (refused: Refused) {
                made.discard()
                throw refused
            }
            try {
                backups.keep(original, store.fileName, now)
            } catch (failure: Exception) {
                made.discard()
                backups.notSaved()
                throw Refused(SaveFailure.WriteFailed(failure.message ?: failure.javaClass.simpleName))
            }
            try {
                if (stopped()) throw Refused(SaveFailure.Locked)
                store.replace(made.file, current.version)
            } catch (_: DatabaseChangedException) {
                // Changed since that read (a sync, at the last moment) and nothing was replaced: read it again, as above.
                made.discard()
                backups.notSaved()
                return@repeat
            } catch (refused: Refused) {
                made.discard()
                backups.notSaved()
                throw refused
            } catch (failure: DatabaseReplacedException) {
                // Replaced all the same, its new time not made sure of: the copy is the replaced file's backup.
                made.discard()
                backups.saved()
                throw Refused(SaveFailure.WriteFailed(failure.message ?: failure.javaClass.simpleName))
            } catch (failure: Exception) {
                made.discard()
                backups.notSaved()
                throw Refused(SaveFailure.WriteFailed(failure.message ?: failure.javaClass.simpleName))
            }
            backups.saved()
            val version = store.version()
            if (version == null) {
                made.discard()
                throw Refused(SaveFailure.WriteFailed("the new file is gone"))
            }
            return SaveResult.Saved(made.reread.toOpenedVault(version, made.vault), made.deletedGroups)
        }
        throw Refused(SaveFailure.FileKeepsChanging)
    }

    private fun read(): ByteArray = when (val read = store.read()) {
        is DatabaseRead.Found -> read.bytes
        DatabaseRead.Missing -> throw Refused(SaveFailure.CannotOpen(OpenFailure.Missing))
        DatabaseRead.AccessDenied -> throw Refused(SaveFailure.CannotOpen(OpenFailure.AccessDenied))
    }

    /** The new file, read back and checked; [vault] is what an unlock would show of it. */
    private class Made(val file: ByteArray, val reread: KdbxDatabase, val vault: Vault, val deletedGroups: List<VaultGroup>) {
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
            // A KDBX 3.1 file (turned into one elsewhere since it was read, or never read here) is never changed.
            if (failure is KdbxException.UnsupportedVersion && failure.major == 3) throw Refused(SaveFailure.ViewOnly)
            throw Refused(SaveFailure.CannotOpen(failure.toOpenFailure()))
        }
        var before: Vault? = null
        try {
            checkAsLoaded(editable.document, edit)
            before = VaultAssembler.assemble(editable.document, editable.binaries.map(Secret::size))
            // The protection Notes written where an entry had none take (SPEC §6.4): the database's, read before the edit.
            val notes = SaveCheck.NewNotes(
                editable.protectsNotes,
                (edit as? VaultEdit.Update)?.entryId?.takeIf { id ->
                    editable.document.entries.firstOrNull { it.uuid == id }?.fields?.none { it.key == "Notes" } == true
                },
            )
            val applied = try {
                editable.apply(edit, now)
            } catch (failure: EditException) {
                throw Refused(failure.toSaveFailure())
            }
            // Attachments nothing refers to any more are dropped; every reference must still mean the same bytes.
            // Nothing KeePass's schema does not allow may be added (SPEC §3.3).
            val written = try {
                editable.write()
            } catch (failure: EditException.BreaksFormat) {
                throw Refused(SaveFailure.BreaksFormat(failure.what))
            } catch (failure: EditException) {
                throw Refused(SaveFailure.VerificationFailed(failure.message ?: "attachments"))
            }

            // Read back what was written, as an unlock would, with the derived key that opened the original: this proves
            // the whole file but the derivation, which is the original's (the same key, KDF parameters and salt).
            val reread = try {
                editable.readBack(written.file)
            } catch (_: KdbxException) {
                throw Refused(SaveFailure.VerificationFailed("the new file does not open"))
            }
            var after: Vault? = null
            try {
                after = VaultAssembler.assemble(reread.document, reread.binaries.map(Secret::size))
                try {
                    SaveCheck.verify(before, after, edit, applied, now, groups(editable.document), groups(reread.document), notes)
                } catch (failure: SaveCheck.Failed) {
                    throw Refused(SaveFailure.VerificationFailed(failure.detail))
                }
                if (reread.binaries != written.binaries) throw Refused(SaveFailure.VerificationFailed("attachments differ"))
                // As they were: the check found these gone and nothing else.
                val groups = before.groups.associateBy { it.id }
                return Made(written.file, reread, after, applied.deletedGroupIds.mapNotNull(groups::get))
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

    /** [original], a KDBX 3.1 file, written as KDBX 4.0; then that read back and checked: nothing in it changed. */
    private fun upgraded(original: ByteArray, key: DatabaseKey): Made {
        val editable = try {
            KdbxReader.openForUpgrade(original, key)
        } catch (failure: KdbxException) {
            // Upgraded elsewhere since it was read: read it again.
            if (failure is KdbxException.UnsupportedVersion && failure.major == 4) throw Refused(SaveFailure.ChangedElsewhere)
            throw Refused(SaveFailure.CannotOpen(failure.toOpenFailure()))
        }
        var before: Vault? = null
        try {
            before = VaultAssembler.assemble(editable.document, editable.binaries.map(Secret::size))
            val written = try {
                editable.write()
            } catch (failure: EditException.BreaksFormat) {
                throw Refused(SaveFailure.BreaksFormat(failure.what))
            } catch (failure: EditException) {
                throw Refused(SaveFailure.VerificationFailed(failure.message ?: "attachments"))
            }
            val reread = try {
                editable.readBack(written.file)
            } catch (_: KdbxException) {
                throw Refused(SaveFailure.VerificationFailed("the new file does not open"))
            }
            var after: Vault? = null
            try {
                if (!reread.format.version.startsWith("4.")) throw Refused(SaveFailure.VerificationFailed("the new file is not KDBX 4"))
                after = VaultAssembler.assemble(reread.document, reread.binaries.map(Secret::size))
                try {
                    SaveCheck.verifyUnchanged(before, after, groups(editable.document), groups(reread.document))
                } catch (failure: SaveCheck.Failed) {
                    throw Refused(SaveFailure.VerificationFailed(failure.detail))
                }
                if (reread.binaries != written.binaries) throw Refused(SaveFailure.VerificationFailed("attachments differ"))
                return Made(written.file, reread, after, emptyList())
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

    /** Every group of the file in file order, the recycle bin's own too. */
    private fun groups(document: KdbxDocument): List<VaultGroup> = document.groups.map { VaultGroup(it.uuid, it.name, it.path) }

    /** The entry must be in the file as it was loaded: a different `LastModificationTime` means changed elsewhere. */
    private fun checkAsLoaded(document: KdbxDocument, edit: VaultEdit) {
        if (edit !is VaultEdit.EntryEdit) return
        val existing = document.entries.firstOrNull { it.uuid == edit.entryId }
        val loaded = when (edit) {
            is VaultEdit.Create -> {
                if (existing != null) throw Refused(SaveFailure.VerificationFailed("the new entry's id is taken"))
                return
            }
            is VaultEdit.Update -> edit.loadedModified
            is VaultEdit.MoveToRecycleBin -> edit.loadedModified
            is VaultEdit.Restore -> edit.loadedModified
            is VaultEdit.DeleteForGood -> edit.loadedModified
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
        is EditException.InvalidName -> SaveFailure.InvalidName
        is EditException.GroupNameTaken -> SaveFailure.GroupNameTaken
        is EditException.GroupNotChangeable -> SaveFailure.GroupNotChangeable
        is EditException.GroupNotEmpty -> SaveFailure.GroupNotEmpty
    }

    /** A save that wrote nothing, and why. */
    private class Refused(val failure: SaveFailure) : Exception()

    internal companion object {
        /** The file read once more when it changed during the save, then the save gives up. */
        private const val ATTEMPTS = 2

        /** The name the 3.1 file is kept under beside the database (keething-spec kdbx.kdbx3 AC7): `<stem> (KDBX 3.1)<extension>`. */
        fun copyName(fileName: String): String {
            val dot = fileName.lastIndexOf('.').takeIf { it > 0 } ?: fileName.length
            return "${fileName.substring(0, dot)} (KDBX 3.1)${fileName.substring(dot)}"
        }
    }
}
