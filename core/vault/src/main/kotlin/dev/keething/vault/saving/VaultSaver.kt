package dev.keething.vault.saving

import dev.keething.domain.secret.Secret
import dev.keething.domain.storage.BackupDamagedException
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
 * The file a save replaces is backed up once a session (keething-spec databases.backups AC1): not when the session wrote
 * it ([forget] ends the session), not when a backup has the same bytes, not when backups are off.
 * Blocks: call it off the main thread.
 */
internal class VaultSaver(private val store: DatabaseStore, private val backups: DatabaseBackups) {
    /** The SHA-256 of the file the session's last save wrote, and which session that was; guarded by itself. */
    private val written = Written()

    private class Written {
        var session = 0
        var sha256: ByteArray? = null
    }

    /**
     * The session ended (a lock): the next save backs the file up as it is, unless a backup has its bytes. A save the
     * lock overtook that still replaces the file leaves nothing for the next session.
     */
    fun forget() = synchronized(written) {
        written.session++
        written.sha256 = null
    }

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
            if (result is SaveResult.Saved) SaveResult.Saved(result.opened, keptCopy = keptAs, mayNotSync = result.mayNotSync) else result
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
        // The copy's bytes came from another device and were never backed up: the next save backs them up.
        val result = refusing { saveOrRefuse(stopped, make = { original -> copied(original, copyName, key) }, now = now, ours = false) }
        if (result !is SaveResult.Saved) return result
        val deleted = try {
            store.deleteConflictCopy(copyName)
            true
        } catch (_: Exception) {
            false
        }
        return if (deleted) result else SaveResult.Saved(result.opened, copyLeft = copyName, mayNotSync = result.mayNotSync)
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

    /**
     * Restores the backup named [name] (keething-spec databases.backups-window AC6-AC8): the database's file replaced
     * with the backup's bytes as they are, once they are those kept and their header is whole. [key]: the key the
     * database is open with, null while it is locked; the backup is opened with it first, and that read is the file read
     * back. One it does not open (made before a master key change) is restored all the same, [RestoreResult.Restored.olderKey];
     * one that does not read for another reason is not. Saved like any change, only while the file is what was read, but
     * the file as it is is backed up whatever the session wrote and whether backups are on, marked as the file before a
     * restore, unless a backup has the same bytes already (an Undo adds no copy of the backup it undoes); that backup is
     * the result's [RestoreResult.Restored.beforeRestoring]. The new file is not the session's: its next save backs it up
     * unless a backup has its bytes (the one restored does). Anything else leaves the file and the backups as they were.
     */
    fun restore(name: String, key: DatabaseKey?, now: Instant): RestoreResult {
        val session = synchronized(written) { written.session }
        var made: Made? = null
        return try {
            val bytes = try {
                backups.read(name)
            } catch (_: BackupDamagedException) {
                throw Refused(SaveFailure.BackupDamaged)
            } ?: throw Refused(SaveFailure.BackupGone)
            if (KdbxReader.isHeaderDamaged(bytes.inputStream())) throw Refused(SaveFailure.BackupDamaged)
            made = key?.let { opened(bytes, it) }
            val olderKey = key != null && made == null
            repeat(ATTEMPTS) {
                val first = Original(store)
                first.open({}, {})
                // The same bytes kept already (an Undo: the backup it undoes): that one is the file before the restore.
                val held = try {
                    backups.holding(first.sha256)
                } catch (_: Exception) {
                    null
                }
                var kept: String? = null
                val version = backUp(first, now, stopped = { false }, copy = held == null) { input ->
                    kept = backups.keepBeforeRestoring(input, store.fileName, now)
                } ?: return@repeat
                var mayNotSync = false
                try {
                    store.replace(bytes, version)
                } catch (_: DatabaseChangedException) {
                    if (held == null) backups.notSaved()
                    return@repeat
                } catch (_: DatabaseReplacedException) {
                    // Replaced all the same, its new time not made sure of: restored, said with a warning (databases.saf AC6).
                    mayNotSync = true
                } catch (failure: Exception) {
                    if (held == null) backups.notSaved()
                    throw Refused(SaveFailure.WriteFailed(failure.message ?: failure.javaClass.simpleName))
                }
                if (held == null) backups.restored(name)
                wrote(session, bytes, ours = false)
                val saved = store.version() ?: throw Refused(SaveFailure.WriteFailed("the new file is gone")) // English detail
                val opened = made?.let { it.reread.toOpenedVault(saved, bytes.size.toLong(), it.vault) }
                made = null
                return RestoreResult.Restored(opened, olderKey, beforeRestoring = checkNotNull(kept ?: held), mayNotSync = mayNotSync)
            }
            throw Refused(SaveFailure.FileKeepsChanging)
        } catch (refused: Refused) {
            RestoreResult.Failed(refused.failure)
        } catch (_: WipedKeyException) {
            RestoreResult.Failed(SaveFailure.Locked)
        } catch (failure: IOException) {
            RestoreResult.Failed(SaveFailure.WriteFailed(failure.message ?: failure.javaClass.simpleName))
        } catch (_: OutOfMemoryError) {
            RestoreResult.Failed(SaveFailure.OutOfMemory)
        } finally {
            made?.discard()
        }
    }

    /**
     * A backup's [bytes] opened with [key], as an unlock would read them; null when the key is rejected (a backup made
     * before a master key change). Anything else that keeps them from reading refuses the restore.
     */
    private fun opened(bytes: ByteArray, key: DatabaseKey): Made? {
        val reread = try {
            KdbxReader.open(bytes, key)
        } catch (_: KdbxException.WrongPassword) {
            return null
        } catch (failure: KdbxException) {
            throw Refused(SaveFailure.BackupNotOpened(failure.toOpenFailure()))
        }
        val vault = try {
            VaultAssembler.assemble(reread.document, reread.binaries.map(Secret::size))
        } catch (failure: RuntimeException) {
            reread.binaries.forEach(Secret::wipe)
            throw failure
        }
        return Made(bytes, reread, vault, emptyList())
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
     * whole, as it needs the bytes. [ours]: the new file counts as the session's own, so its next save does not back it up.
     */
    private fun saveOrRefuse(
        stopped: () -> Boolean,
        make: (original: Original) -> Made,
        now: Instant,
        ours: Boolean = true,
        beforeBackup: ((original: ByteArray) -> Unit)? = null,
    ): SaveResult {
        val session = synchronized(written) { written.session }
        repeat(ATTEMPTS) {
            val first = Original(store)
            val made = make(first)
            // Decided for each read: a file changed elsewhere since the last attempt is not the session's.
            val copy = needsBackup(first)
            // The file must still be what was read; otherwise read it again and redo the edit on top.
            val version = try {
                if (beforeBackup == null) backUp(first, now, stopped, copy) else backUpWhole(first, now, stopped, beforeBackup, copy)
            } catch (refused: Refused) {
                made.discard()
                throw refused
            }
            if (version == null) {
                made.discard()
                return@repeat
            }
            var mayNotSync = false
            try {
                if (stopped()) throw Refused(SaveFailure.Locked)
                store.replace(made.file, version)
            } catch (_: DatabaseChangedException) {
                // Changed since that read (a sync, at the last moment) and nothing was replaced: read it again, as above.
                made.discard()
                if (copy) backups.notSaved()
                return@repeat
            } catch (refused: Refused) {
                made.discard()
                if (copy) backups.notSaved()
                throw refused
            } catch (_: DatabaseReplacedException) {
                // Replaced all the same, its new time not made sure of: saved, said with a warning (databases.saf AC6).
                mayNotSync = true
            } catch (failure: Exception) {
                made.discard()
                if (copy) backups.notSaved()
                throw Refused(SaveFailure.WriteFailed(failure.message ?: failure.javaClass.simpleName))
            }
            if (copy) backups.saved()
            wrote(session, made.file, ours)
            val saved = store.version()
            if (saved == null) {
                made.discard()
                throw Refused(SaveFailure.WriteFailed("the new file is gone")) // English detail
            }
            return SaveResult.Saved(made.reread.toOpenedVault(saved, made.file.size.toLong(), made.vault), made.deletedGroups, mayNotSync = mayNotSync)
        }
        throw Refused(SaveFailure.FileKeepsChanging)
    }

    /**
     * Whether the file [first] read is backed up before it is replaced (keething-spec databases.backups AC1): backups on,
     * not the file the session wrote, no backup of the same bytes. A backup that cannot be looked through counts as none.
     */
    private fun needsBackup(first: Original): Boolean {
        if (!backups.on) return false
        val sha256 = first.sha256
        val ours = synchronized(written) { written.sha256?.let { MessageDigest.isEqual(it, sha256) } == true }
        if (ours) return false
        return try {
            !backups.holds(sha256)
        } catch (_: Exception) {
            true
        }
    }

    /** [file] replaced the database in [session]: the session's own from now on, unless not [ours] or the session ended. */
    private fun wrote(session: Int, file: ByteArray, ours: Boolean) {
        val sha256 = if (ours) MessageDigest.getInstance("SHA-256").digest(file) else null
        synchronized(written) { if (written.session == session) written.sha256 = sha256 }
    }

    /**
     * Reads the file again and, when [copy], keeps the backup from it as it is read ([keep]), never holding it whole: its
     * version when it is still what [first] read, else null with no copy kept. A save [stopped] by then stops before the
     * backup.
     */
    private fun backUp(
        first: Original,
        now: Instant,
        stopped: () -> Boolean,
        copy: Boolean,
        keep: (InputStream) -> Unit = { backups.keep(it, store.fileName, now) },
    ): FileVersion? {
        val digest = MessageDigest.getInstance("SHA-256")
        var keeping = false
        val read = try {
            store.read { input ->
                if (stopped()) throw Refused(SaveFailure.Locked)
                val digesting = DigestInputStream(input, digest)
                if (copy) {
                    keeping = true
                    keep(digesting)
                }
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
    private fun backUpWhole(
        first: Original,
        now: Instant,
        stopped: () -> Boolean,
        beforeBackup: (original: ByteArray) -> Unit,
        copy: Boolean,
    ): FileVersion? {
        val current = store.read()
        if (current !is DatabaseRead.Found || !first.isIn(current.bytes)) return null
        if (stopped()) throw Refused(SaveFailure.Locked)
        beforeBackup(current.bytes)
        if (!copy) return current.version
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
        fun hasDigest(sha256: ByteArray): Boolean = MessageDigest.isEqual(sha256, this.sha256)

        /** The SHA-256 of the file as it was read. */
        val sha256: ByteArray get() = checkNotNull(digest) { "not opened" }

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
                    editable.document.entries.firstOrNull { it.uuid == id }?.fields?.none { it.key == "Notes" } == true // not user text
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
                throw Refused(SaveFailure.VerificationFailed("the new file does not open")) // English detail
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
                throw Refused(SaveFailure.VerificationFailed("the new file does not open")) // English detail
            } catch (failure: EditException) {
                throw Refused(SaveFailure.VerificationFailed(failure.message ?: "attachments"))
            }
            var after: Vault? = null
            try {
                if (!reread.format.version.startsWith("4.")) throw Refused(SaveFailure.VerificationFailed("the new file is not KDBX 4")) // English detail
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
                throw Refused(SaveFailure.VerificationFailed(failure.message ?: "the new key derivation")) // English detail
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
                throw Refused(SaveFailure.VerificationFailed("the new file does not open with the new key")) // English detail
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

    /**
     * The entry must be in the file as it was loaded, and the only one with its id: a different `LastModificationTime`
     * means changed elsewhere.
     */
    private fun checkAsLoaded(document: KdbxDocument, edit: VaultEdit) {
        if (edit !is VaultEdit.EntryEdit) return
        val existing = document.entries.firstOrNull { it.uuid == edit.entryId }
        val loaded = when (edit) {
            is VaultEdit.Create -> {
                if (existing != null) throw Refused(SaveFailure.VerificationFailed("the new entry's id is taken")) // English detail
                return
            }
            is VaultEdit.Update -> edit.loadedModified
            is VaultEdit.MoveToRecycleBin -> edit.loadedModified
            is VaultEdit.Restore -> edit.loadedModified
            is VaultEdit.DeleteForGood -> edit.loadedModified
            is VaultEdit.RestoreVersion -> edit.loadedModified
        }
        if (existing == null) throw Refused(SaveFailure.EntryNotFound)
        if (document.entries.count { it.uuid == edit.entryId } > 1) throw Refused(SaveFailure.DuplicateId)
        if (!sameTime(loaded, existing.lastModified)) throw Refused(SaveFailure.ChangedElsewhere)
        // The version as it was read, not another one in its place.
        if (edit is VaultEdit.RestoreVersion) {
            val version = document.history.getOrNull(edit.version)
            if (version == null || !sameTime(edit.versionModified, version.lastModified)) throw Refused(SaveFailure.VersionGone)
        }
    }

    /**
     * Within a second, as KeePass's times are kept. A time not known matches only another not known: a time on one side
     * alone means the entry changed elsewhere (keething-spec kdbx.save AC2).
     */
    private fun sameTime(loaded: Instant?, inFile: Instant?): Boolean =
        if (loaded == null || inFile == null) loaded == inFile else Duration.between(loaded, inFile).abs() < Duration.ofSeconds(1)

    private fun EditException.toSaveFailure(): SaveFailure = when (this) {
        is EditException.EntryNotFound -> SaveFailure.EntryNotFound
        is EditException.GroupNotFound -> SaveFailure.GroupNotFound
        is EditException.RecycleBinDisabled -> SaveFailure.RecycleBinDisabled
        is EditException.AlreadyInRecycleBin, is EditException.NotInRecycleBin -> SaveFailure.WrongPlace
        is EditException.EntryExists -> SaveFailure.VerificationFailed("the new entry's id is taken") // English detail
        is EditException.MalformedLinks -> SaveFailure.VerificationFailed("the recycle bin's groups point to each other in a circle") // English detail
        is EditException.Malformed -> SaveFailure.VerificationFailed(message ?: "malformed")
        is EditException.BreaksFormat -> SaveFailure.BreaksFormat(what)
        is EditException.InvalidName -> SaveFailure.InvalidName
        is EditException.GroupNameTaken -> SaveFailure.GroupNameTaken
        is EditException.GroupNotChangeable -> SaveFailure.GroupNotChangeable
        is EditException.GroupNotEmpty -> SaveFailure.GroupNotEmpty
        // The entry as loaded had it, or the save would have been refused as changed elsewhere.
        is EditException.AttachmentNotFound -> SaveFailure.VerificationFailed("an attachment to keep is gone") // English detail
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
