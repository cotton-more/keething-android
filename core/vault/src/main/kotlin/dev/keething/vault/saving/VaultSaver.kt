package dev.keething.vault.saving

import dev.keething.domain.secret.Secret
import dev.keething.domain.storage.DatabaseBackups
import dev.keething.domain.storage.DatabaseChangedException
import dev.keething.domain.storage.DatabaseRead
import dev.keething.domain.storage.DatabaseReading
import dev.keething.domain.storage.DatabaseReplacedException
import dev.keething.domain.storage.DatabaseStore
import dev.keething.domain.storage.FileVersion
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.FieldValue
import dev.keething.domain.vault.Vault
import dev.keething.domain.vault.VaultEdit
import dev.keething.domain.vault.VaultGroup
import dev.keething.kdbx.editing.EditException
import dev.keething.kdbx.editing.EditableDatabase
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.kdbx.key.WipedKeyException
import dev.keething.kdbx.reader.KdbxDatabase
import dev.keething.kdbx.reader.KdbxException
import dev.keething.kdbx.reader.KdbxReader
import dev.keething.kdbx.xml.KdbxDocument
import dev.keething.vault.opening.EntryMapper
import dev.keething.vault.opening.OpenFailure
import dev.keething.vault.opening.VaultAssembler
import dev.keething.vault.opening.toOpenFailure
import dev.keething.vault.opening.toOpenedVault
import java.io.IOException
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

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

    /**
     * Makes [newKey] the database's master key (keething-spec databases.change-master-key AC4, AC5): the file opened
     * with [key], the key it has, written with [newKey] and a new salt for its key derivation, the costs as they were,
     * `Meta/MasterKeyChanged` set; read back with what [newKey] derived for it and checked to hold everything as before.
     * Saved like any change (backed up, replaced only while the file is what was read); anything else leaves the file
     * as it was. [newKey] stays the caller's, with the key derived for the new file in it.
     */
    fun changeKey(newKey: DatabaseKey, key: DatabaseKey, now: Instant, stopped: () -> Boolean = { false }): SaveResult =
        refusing { saveOrRefuse(stopped, make = { original -> rekeyed(original, key, newKey, now) }, now = now) }

    /**
     * Takes the conflict copy named [copyName] in the database's place (keething-spec kdbx.conflict-copies AC6): its bytes
     * as they are, once they open with [key], the database's; saved like any change (the database backed up, replaced only
     * while it is what was read). The copy is deleted afterwards, the database holding it now; one that stays is
     * [SaveResult.Saved.copyLeft]. A copy gone is [SaveFailure.CopyGone]; one that does not open,
     * [SaveFailure.CopyNotOpened], nothing replaced.
     */
    fun takeCopy(copyName: String, key: DatabaseKey, now: Instant, stopped: () -> Boolean = { false }): SaveResult {
        val result = refusing { saveOrRefuse(stopped, make = { original -> copied(original, copyName, key) }, now = now) }
        if (result !is SaveResult.Saved) return result
        val deleted = try {
            store.deleteConflictCopy(copyName)
            true
        } catch (_: Exception) {
            false
        }
        return if (deleted) result else SaveResult.Saved(result.opened, copyLeft = copyName)
    }

    /**
     * Deletes the conflict copy named [copyName], the database kept as it is (keething-spec kdbx.conflict-copies AC6): the
     * copy is kept among the database's backups first, under its own name. Null when it is gone (also when it was not
     * there), else why it stays.
     */
    fun removeCopy(copyName: String, now: Instant): SaveFailure? {
        val bytes = try {
            store.readConflictCopy(copyName) ?: return null
        } catch (failure: IOException) {
            return SaveFailure.WriteFailed(failure.message ?: failure.javaClass.simpleName)
        } catch (_: OutOfMemoryError) {
            return SaveFailure.OutOfMemory
        }
        try {
            backups.keep(bytes, copyName, now)
        } catch (failure: Exception) {
            backups.notSaved()
            return SaveFailure.WriteFailed(failure.message ?: failure.javaClass.simpleName)
        }
        try {
            store.deleteConflictCopy(copyName)
        } catch (failure: Exception) {
            backups.notSaved()
            return SaveFailure.WriteFailed(failure.message ?: failure.javaClass.simpleName)
        }
        backups.saved()
        return null
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

    /**
     * [make] opens the file as it is read ([Original]): the file is never held whole, since a large one costs memory the
     * save needs (keething-spec app.large-database AC2). It is read again once the new file is made, as a stream too: its
     * SHA-256 must be the first read's, and the backup is written from it as it comes. [beforeBackup]
     * (an upgrade's): what is done once the new file is made and the old one is still what was read; that read is held
     * whole, as it needs the bytes.
     */
    private fun saveOrRefuse(
        stopped: () -> Boolean,
        make: (original: Original) -> Made,
        now: Instant,
        beforeBackup: ((original: ByteArray) -> Unit)? = null,
    ): SaveResult {
        repeat(ATTEMPTS) {
            val first = Original(store)
            val made = make(first)
            // The file must still be what was read; otherwise read it again and redo the edit on top.
            val version = try {
                if (beforeBackup == null) backUp(first, now, stopped) else backUpWhole(first, now, stopped, beforeBackup)
            } catch (refused: Refused) {
                made.discard()
                throw refused
            }
            if (version == null) {
                made.discard()
                return@repeat
            }
            try {
                if (stopped()) throw Refused(SaveFailure.Locked)
                store.replace(made.file, version)
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
            val saved = store.version()
            if (saved == null) {
                made.discard()
                throw Refused(SaveFailure.WriteFailed("the new file is gone"))
            }
            return SaveResult.Saved(made.reread.toOpenedVault(saved, made.file.size.toLong(), made.vault), made.deletedGroups)
        }
        throw Refused(SaveFailure.FileKeepsChanging)
    }

    /**
     * Reads the file again and keeps the backup from it as it is read, never holding it whole: its version when it is
     * still what [first] read, else null with no copy kept. A save [stopped] by then stops before the backup.
     */
    private fun backUp(first: Original, now: Instant, stopped: () -> Boolean): FileVersion? {
        val digest = MessageDigest.getInstance("SHA-256")
        var keeping = false
        val read = try {
            store.read { input ->
                if (stopped()) throw Refused(SaveFailure.Locked)
                val digesting = DigestInputStream(input, digest)
                keeping = true
                backups.keep(digesting, store.fileName, now)
                // To the end whatever the backup read: the digest is the whole file's.
                val rest = ByteArray(64 * 1024)
                while (digesting.read(rest) >= 0) Unit
            }
        } catch (failure: Throwable) {
            if (keeping) backups.notSaved()
            if (failure !is Exception || failure is Refused) throw failure
            throw Refused(SaveFailure.WriteFailed(failure.message ?: failure.javaClass.simpleName))
        }
        if (read !is DatabaseReading.Found || !first.hasDigest(digest.digest())) {
            if (keeping) backups.notSaved()
            return null
        }
        return read.version
    }

    /** As [backUp], with the file read whole for [beforeBackup] first. */
    private fun backUpWhole(first: Original, now: Instant, stopped: () -> Boolean, beforeBackup: (original: ByteArray) -> Unit): FileVersion? {
        val current = store.read()
        if (current !is DatabaseRead.Found || !first.isIn(current.bytes)) return null
        if (stopped()) throw Refused(SaveFailure.Locked)
        beforeBackup(current.bytes)
        try {
            backups.keep(current.bytes, store.fileName, now)
        } catch (failure: Exception) {
            backups.notSaved()
            throw Refused(SaveFailure.WriteFailed(failure.message ?: failure.javaClass.simpleName))
        }
        return current.version
    }

    /**
     * The file a save opens, read as it is opened ([open]), never held whole (keething-spec app.large-database AC2):
     * afterwards only its SHA-256 is kept, what the file must still be when it is replaced.
     */
    private class Original(private val store: DatabaseStore) {
        private var digest: ByteArray? = null

        /**
         * Reads the file into [opening], to its end for the digest; [discard] takes back what [opening] made when the
         * read fails after it.
         */
        fun <T> open(opening: (InputStream) -> T, discard: (T) -> Unit): T {
            val sha256 = MessageDigest.getInstance("SHA-256")
            val read = store.read { input ->
                val digesting = DigestInputStream(input, sha256)
                val opened = opening(digesting)
                try {
                    val rest = ByteArray(64 * 1024)
                    while (digesting.read(rest) >= 0) Unit
                } catch (failure: Throwable) {
                    discard(opened)
                    throw failure
                }
                opened
            }
            return when (read) {
                is DatabaseReading.Found -> read.result.also { digest = sha256.digest() }
                DatabaseReading.Missing -> throw Refused(SaveFailure.CannotOpen(OpenFailure.Missing))
                DatabaseReading.AccessDenied -> throw Refused(SaveFailure.CannotOpen(OpenFailure.AccessDenied))
            }
        }

        /** Whether [current] are the same bytes. */
        fun isIn(current: ByteArray): Boolean = hasDigest(sha256(current))

        /** Whether bytes of the SHA-256 [sha256] are the same bytes. */
        fun hasDigest(sha256: ByteArray): Boolean = MessageDigest.isEqual(sha256, checkNotNull(digest) { "not opened" })

        private companion object {
            fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        }
    }

    /** The new file, read back and checked; [vault] is what an unlock would show of it. */
    private class Made(val file: ByteArray, val reread: KdbxDatabase, val vault: Vault, val deletedGroups: List<VaultGroup>) {
        fun discard() {
            vault.wipe()
            reread.binaries.forEach(Secret::wipe)
        }
    }

    /** Opens [original], applies the change and writes the new file; then reads that back and checks it. */
    private fun makeFile(original: Original, edit: VaultEdit, key: DatabaseKey, now: Instant): Made {
        val editable = try {
            // A version restored is checked against the entry's history as the file has it.
            original.open({ KdbxReader.openForEditing(it, key, historyOf = (edit as? VaultEdit.RestoreVersion)?.entryId) }, EditableDatabase::wipe)
        } catch (failure: KdbxException) {
            // A KDBX 3.1 file (turned into one elsewhere since it was read, or never read here) is never changed.
            if (failure is KdbxException.UnsupportedVersion && failure.major == 3) throw Refused(SaveFailure.ViewOnly)
            throw Refused(SaveFailure.CannotOpen(failure.toOpenFailure()))
        }
        var before: Vault? = null
        var restored: Entry? = null
        try {
            checkAsLoaded(editable.document, edit)
            before = VaultAssembler.assemble(editable.document, editable.binaries.map(Secret::size))
            if (edit is VaultEdit.RestoreVersion) restored = restoredVersion(editable.document, editable.binaries, edit)
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
                editable.writeLast()
            } catch (failure: EditException.BreaksFormat) {
                throw Refused(SaveFailure.BreaksFormat(failure.what))
            } catch (failure: EditException) {
                throw Refused(SaveFailure.VerificationFailed(failure.message ?: "attachments"))
            }

            // Read back what was written, as an unlock would, with the derived key that opened the original: this proves
            // the whole file but the derivation, which is the original's (the same key, KDF parameters and salt).
            val reread = try {
                editable.readBack(written)
            } catch (_: KdbxException) {
                throw Refused(SaveFailure.VerificationFailed("the new file does not open"))
            } catch (failure: EditException) {
                throw Refused(SaveFailure.VerificationFailed(failure.message ?: "attachments"))
            }
            var after: Vault? = null
            try {
                after = VaultAssembler.assemble(reread.document, reread.binaries.map(Secret::size))
                try {
                    SaveCheck.verify(before, after, edit, applied, now, groups(editable.document), groups(reread.document), notes, restored)
                    SaveCheck.verifyAttachmentContents(edit, editable.document, editable.binaries, reread.document, reread.binaries)
                } catch (failure: SaveCheck.Failed) {
                    throw Refused(SaveFailure.VerificationFailed(failure.detail))
                }
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
            restored?.wipe()
            editable.wipe()
        }
    }

    /**
     * The version [edit] restores as the file has it now, to check the restored entry against: the entry's own place
     * and creation time, which a restore keeps. Its secrets are copies, the caller's to wipe.
     */
    private fun restoredVersion(document: KdbxDocument, binaries: List<Secret>, edit: VaultEdit.RestoreVersion): Entry {
        val current = document.entries.first { it.uuid == edit.entryId }
        val raw = document.history.getOrNull(edit.version) ?: throw Refused(SaveFailure.VersionGone)
        // A version read is its document's: the mapper takes a copy of each secret, the document wiping its own.
        val copy = raw.copy(fields = raw.fields.map { field -> field.copy(value = (field.value as? FieldValue.Protected)?.let { FieldValue.Protected(it.secret.copy()) } ?: field.value) })
        val version = EntryMapper(binaries.map(Secret::size), document.meta.customIcons.keys).map(copy)
        return version.copy(groupPath = current.groupPath, groupId = current.ancestorGroupUuids.lastOrNull() ?: "", created = current.created)
    }

    /** [original], a KDBX 3.1 file, written as KDBX 4.0; then that read back and checked: nothing in it changed. */
    private fun upgraded(original: Original, key: DatabaseKey): Made {
        val editable = try {
            // A KDBX 3.1 file is read whole: its reader takes the bytes.
            original.open({ KdbxReader.openForUpgrade(it.readBytes(), key) }, EditableDatabase::wipe)
        } catch (failure: KdbxException) {
            // Upgraded elsewhere since it was read: read it again.
            if (failure is KdbxException.UnsupportedVersion && failure.major == 4) throw Refused(SaveFailure.ChangedElsewhere)
            throw Refused(SaveFailure.CannotOpen(failure.toOpenFailure()))
        }
        var before: Vault? = null
        try {
            before = VaultAssembler.assemble(editable.document, editable.binaries.map(Secret::size))
            val written = try {
                editable.writeLast()
            } catch (failure: EditException.BreaksFormat) {
                throw Refused(SaveFailure.BreaksFormat(failure.what))
            } catch (failure: EditException) {
                throw Refused(SaveFailure.VerificationFailed(failure.message ?: "attachments"))
            }
            val reread = try {
                editable.readBack(written)
            } catch (_: KdbxException) {
                throw Refused(SaveFailure.VerificationFailed("the new file does not open"))
            } catch (failure: EditException) {
                throw Refused(SaveFailure.VerificationFailed(failure.message ?: "attachments"))
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

    /** [original] written with [newKey] (its own salt); then that read back with it alone and checked: nothing in it changed. */
    private fun rekeyed(original: Original, key: DatabaseKey, newKey: DatabaseKey, now: Instant): Made {
        val editable = try {
            original.open({ KdbxReader.openForEditing(it, key) }, EditableDatabase::wipe)
        } catch (failure: KdbxException) {
            if (failure is KdbxException.UnsupportedVersion && failure.major == 3) throw Refused(SaveFailure.ViewOnly)
            throw Refused(SaveFailure.CannotOpen(failure.toOpenFailure()))
        }
        var before: Vault? = null
        try {
            before = VaultAssembler.assemble(editable.document, editable.binaries.map(Secret::size))
            try {
                editable.changeKey(newKey, now)
            } catch (failure: KdbxException) {
                throw Refused(SaveFailure.VerificationFailed(failure.message ?: "the new key derivation"))
            }
            val written = try {
                editable.writeLast()
            } catch (failure: EditException.BreaksFormat) {
                throw Refused(SaveFailure.BreaksFormat(failure.what))
            } catch (failure: EditException) {
                throw Refused(SaveFailure.VerificationFailed(failure.message ?: "attachments"))
            }
            val reread = try {
                editable.readBack(written)
            } catch (_: KdbxException) {
                throw Refused(SaveFailure.VerificationFailed("the new file does not open with the new key"))
            } catch (failure: EditException) {
                throw Refused(SaveFailure.VerificationFailed(failure.message ?: "attachments"))
            }
            var after: Vault? = null
            try {
                after = VaultAssembler.assemble(reread.document, reread.binaries.map(Secret::size))
                try {
                    // Its master key's time is the change's, to the second as the file keeps it.
                    val changed = before.copy(masterKeyChanged = now.truncatedTo(ChronoUnit.SECONDS))
                    SaveCheck.verifyUnchanged(changed, after, groups(editable.document), groups(reread.document))
                } catch (failure: SaveCheck.Failed) {
                    throw Refused(SaveFailure.VerificationFailed(failure.detail))
                }
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

    /**
     * The conflict copy named [copyName] as the new file, opened with [key]: written as it is, so its read is its check.
     * [original] is read only for its digest, what the database must still be when the copy replaces it.
     */
    private fun copied(original: Original, copyName: String, key: DatabaseKey): Made {
        original.open({}, {})
        val bytes = try {
            store.readConflictCopy(copyName)
        } catch (failure: IOException) {
            throw Refused(SaveFailure.WriteFailed(failure.message ?: failure.javaClass.simpleName))
        } ?: throw Refused(SaveFailure.CopyGone)
        val reread = try {
            KdbxReader.open(bytes, key)
        } catch (failure: KdbxException) {
            throw Refused(SaveFailure.CopyNotOpened(failure.toOpenFailure()))
        }
        val vault = try {
            VaultAssembler.assemble(reread.document, reread.binaries.map(Secret::size))
        } catch (failure: RuntimeException) {
            reread.binaries.forEach(Secret::wipe)
            throw failure
        }
        return Made(bytes, reread, vault, emptyList())
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
            is VaultEdit.RestoreVersion -> edit.loadedModified
        }
        if (existing == null) throw Refused(SaveFailure.EntryNotFound)
        if (!sameTime(loaded, existing.lastModified)) throw Refused(SaveFailure.ChangedElsewhere)
        // The version as it was read, not another one in its place.
        if (edit is VaultEdit.RestoreVersion) {
            val version = document.history.getOrNull(edit.version)
            if (version == null || !sameTime(edit.versionModified, version.lastModified)) throw Refused(SaveFailure.VersionGone)
        }
    }

    /** Within a second, as KeePass's times are kept; a time not known matches any. */
    private fun sameTime(loaded: Instant?, inFile: Instant?): Boolean =
        loaded == null || inFile == null || Duration.between(loaded, inFile).abs() < Duration.ofSeconds(1)

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
        // The entry as loaded had it, or the save would have been refused as changed elsewhere.
        is EditException.AttachmentNotFound -> SaveFailure.VerificationFailed("an attachment to keep is gone")
        is EditException.VersionNotFound -> SaveFailure.VersionGone
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
