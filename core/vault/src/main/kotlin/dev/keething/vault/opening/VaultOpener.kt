package dev.keething.vault.opening

import dev.keething.domain.secret.Secret
import dev.keething.domain.storage.DatabaseRead
import dev.keething.domain.storage.DatabaseStore
import dev.keething.domain.storage.FileVersion
import dev.keething.domain.storage.StoppedSave
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.FieldValue
import dev.keething.domain.vault.Vault
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.kdbx.key.WipedKeyException
import dev.keething.kdbx.reader.KdbxDatabase
import dev.keething.kdbx.reader.KdbxException
import dev.keething.kdbx.reader.KdbxReader
import dev.keething.vault.session.CopyContents
import dev.keething.vault.session.VaultSummary
import java.io.IOException
import kotlin.time.Duration

/** Why the database could not be opened, in terms the screens can show. Never carries entry data. */
public sealed interface OpenFailure {
    /**
     * The database rejected the key. Of an unlock: a password alone was given (the file cannot tell a typo from a
     * missing key file). Of a reload or a save: the key that opened it no longer does.
     */
    public data object WrongPassword : OpenFailure

    /** An unlock with a password and a key file was rejected. */
    public data object WrongPasswordOrKeyFile : OpenFailure

    /** An unlock with a key file alone was rejected (and, with nothing typed, the empty password with it). */
    public data object WrongKeyFile : OpenFailure

    /** The key file is not at its place: its [name]. Said before the database is read. */
    public data class KeyFileMissing(val name: String) : OpenFailure

    /** The key file cannot be used: [why]. Said before the database is read. */
    public data class KeyFileUnusable(val why: String) : OpenFailure

    /** No file at the place (Syncthing may be replacing it, SPEC §3.2). */
    public data object Missing : OpenFailure

    /** The file may not be read: its grant is gone, or it is kept by its path and its folder not chosen yet. */
    public data object AccessDenied : OpenFailure

    /** A database the app does not read (KDBX 2, Twofish…): [what] names it, [kind] says which, for the words. */
    public data class Unsupported(val what: String, val kind: Kind) : OpenFailure {
        public enum class Kind { NOT_KEEPASS, OLD_FORMAT, NEWER_FORMAT, TWOFISH, CIPHER, KEY_DERIVATION, MEMORY, INNER_STREAM }
    }

    /** Truncated or altered: [detail]. A file caught half-written by a sync is this (SPEC §3.2). */
    public data class Damaged(val detail: String) : OpenFailure

    /** The phone has not enough memory for the key derivation. */
    public data object OutOfMemory : OpenFailure

    /**
     * A lock came while the file was read, and its key was wiped (keething-spec kdbx.hardening AC2, AC6): a key
     * derivation running stopped, nothing was read. The session drops what such a read gives, so screens rarely see it.
     */
    public data object Locked : OpenFailure

    /** The database rejected the key, whatever it was made of. */
    public val isWrongKey: Boolean get() = this == WrongPassword || this == WrongPasswordOrKeyFile || this == WrongKeyFile
}

/** A database just opened. */
internal class OpenedVault(
    val vault: Vault,
    val attachments: AttachmentContents,
    /** The version of the file that was read: a newer one is a reason to read it again. */
    val version: FileVersion,
    /** How long the key derivation took; null when the key derived before was used. */
    val keyDerivation: Duration?,
    /** A save the app did not finish, put right before this read and not said yet (keething-spec databases.saf AC5). */
    val stoppedSave: StoppedSave? = null,
    /** A KDBX 3.1 file: read to be viewed only (keething-spec kdbx.kdbx3 AC6). */
    val viewOnly: Boolean = false,
    /** How large the file read was, and its attachments (keething-spec app.large-database AC3). */
    val size: DatabaseSize = DatabaseSize(0, 0),
)

/** A database as read: the file's bytes, and its attachments' decrypted bytes (keething-spec app.large-database AC3). */
public data class DatabaseSize(val fileBytes: Long, val attachmentBytes: Long)

internal sealed interface OpenResult {
    class Opened(val opened: OpenedVault) : OpenResult

    class Failed(val failure: OpenFailure) : OpenResult
}

/** Reads the database file and opens it with a key. Blocks: call it off the main thread. Open for the tests' openers. */
public open class VaultOpener {
    internal open fun open(store: DatabaseStore, key: DatabaseKey): OpenResult {
        val found = when (val read = store.read()) {
            is DatabaseRead.Found -> read
            DatabaseRead.Missing -> return OpenResult.Failed(OpenFailure.Missing)
            DatabaseRead.AccessDenied -> return OpenResult.Failed(OpenFailure.AccessDenied)
        }
        val database = try {
            KdbxReader.open(found.bytes, key)
        } catch (failure: KdbxException) {
            return OpenResult.Failed(failure.toOpenFailure())
        } catch (_: WipedKeyException) {
            return OpenResult.Failed(OpenFailure.Locked)
        } catch (_: OutOfMemoryError) {
            return OpenResult.Failed(OpenFailure.OutOfMemory)
        }
        return OpenResult.Opened(database.toOpenedVault(found.version, found.bytes.size.toLong(), stoppedSave = found.stoppedSave))
    }

    /**
     * What the conflict copy named [copyName] beside the database holds (keething-spec kdbx.conflict-copies AC6), opened
     * with [key], the database's: only its summary is kept, every secret read wiped at once.
     */
    internal open fun copyContents(store: DatabaseStore, copyName: String, key: DatabaseKey): CopyContents {
        val bytes = try {
            store.readConflictCopy(copyName) ?: return CopyContents.NotOpened(OpenFailure.Missing)
        } catch (_: IOException) {
            return CopyContents.NotOpened(OpenFailure.AccessDenied)
        } catch (_: OutOfMemoryError) {
            return CopyContents.NotOpened(OpenFailure.OutOfMemory)
        }
        val database = try {
            KdbxReader.open(bytes, key)
        } catch (failure: KdbxException) {
            return CopyContents.NotOpened(failure.toOpenFailure())
        } catch (_: WipedKeyException) {
            return CopyContents.NotOpened(OpenFailure.Locked)
        } catch (_: OutOfMemoryError) {
            return CopyContents.NotOpened(OpenFailure.OutOfMemory)
        }
        val sizes = database.binaries.map(Secret::size)
        database.binaries.forEach(Secret::wipe)
        var vault: Vault? = null
        try {
            vault = VaultAssembler.assemble(database.document, sizes)
            return CopyContents.Read(VaultSummary.of(vault))
        } finally {
            vault?.wipe()
            (database.document.entries + database.document.history).forEach { entry ->
                entry.fields.forEach { (it.value as? FieldValue.Protected)?.secret?.wipe() }
            }
        }
    }

    /**
     * The versions of one entry (SPEC §6.2; Mac `KDBXFileLoader.history`): the file read again with [key], keeping
     * that entry's history, which an unlock skips. Every other secret read is wiped at once, the attachments too
     * (a version shows their names and sizes only).
     */
    internal fun history(store: DatabaseStore, key: DatabaseKey, entryId: String): HistoryResult {
        val found = when (val read = store.read()) {
            is DatabaseRead.Found -> read
            DatabaseRead.Missing -> return HistoryResult.Failed(OpenFailure.Missing)
            DatabaseRead.AccessDenied -> return HistoryResult.Failed(OpenFailure.AccessDenied)
        }
        val database = try {
            KdbxReader.open(found.bytes, key, historyOf = entryId)
        } catch (failure: KdbxException) {
            return HistoryResult.Failed(failure.toOpenFailure())
        } catch (_: WipedKeyException) {
            return HistoryResult.Failed(OpenFailure.Locked)
        } catch (_: OutOfMemoryError) {
            return HistoryResult.Failed(OpenFailure.OutOfMemory)
        }
        val document = database.document
        val sizes = database.binaries.map(Secret::size)
        database.binaries.forEach(Secret::wipe)
        val current = document.entries.firstOrNull { it.uuid == entryId }
        for (entry in document.entries) {
            if (entry !== current) entry.fields.forEach { (it.value as? FieldValue.Protected)?.secret?.wipe() }
        }
        if (current == null) {
            document.history.forEach { entry -> entry.fields.forEach { (it.value as? FieldValue.Protected)?.secret?.wipe() } }
            return HistoryResult.Failed(null)
        }
        val mapper = EntryMapper(sizes, document.meta.customIcons.keys)
        return HistoryResult.Read(EntryVersions(mapper.map(current), document.history.map(mapper::map), document.meta.historyMaxItems))
    }
}

/**
 * An entry as the file has it now ([current]) and its earlier versions, oldest first. Holds secrets: [wipe] it
 * when it is not shown any more.
 */
public class EntryVersions(
    public val current: Entry,
    public val history: List<Entry>,
    /** How many earlier versions the database keeps (`HistoryMaxItems`), -1 for any number. */
    public val maxItems: Int = 10,
) {
    public fun wipe() {
        current.wipe()
        history.forEach(Entry::wipe)
    }
}

public sealed interface HistoryResult {
    public class Read(public val versions: EntryVersions) : HistoryResult

    /** Why the file could not be read; null: it has no such entry (any more). */
    public class Failed(public val failure: OpenFailure?) : HistoryResult
}

/**
 * The vault of a database read, with its attachments; [version] is the file's that was read, [fileBytes] its size.
 * [assembled] is its vault when it was made already (a save's read-back).
 */
internal fun KdbxDatabase.toOpenedVault(
    version: FileVersion,
    fileBytes: Long,
    assembled: Vault? = null,
    stoppedSave: StoppedSave? = null,
): OpenedVault {
    val vault = assembled ?: try {
        VaultAssembler.assemble(document, binaries.map(Secret::size))
    } catch (failure: RuntimeException) {
        binaries.forEach(Secret::wipe)
        throw failure
    }
    val indexes = document.entries.associate { entry ->
        entry.uuid to entry.attachments.mapNotNull { ref -> ref.ref?.let { ref.name to it } }.toMap()
    }
    val size = DatabaseSize(fileBytes, binaries.sumOf { it.size.toLong() })
    return OpenedVault(vault, AttachmentContents(binaries, indexes), version, keyDerivation, stoppedSave, viewOnly = format.version.startsWith("3."), size)
}

internal fun KdbxException.toOpenFailure(): OpenFailure = when (this) {
    is KdbxException.WrongPassword -> OpenFailure.WrongPassword
    is KdbxException.Corrupt -> OpenFailure.Damaged(detail)
    is KdbxException.NotKdbx -> unsupported(OpenFailure.Unsupported.Kind.NOT_KEEPASS)
    is KdbxException.UnsupportedVersion ->
        unsupported(if (major > 4) OpenFailure.Unsupported.Kind.NEWER_FORMAT else OpenFailure.Unsupported.Kind.OLD_FORMAT)
    is KdbxException.UnsupportedCipher ->
        unsupported(if (cipher == "Twofish") OpenFailure.Unsupported.Kind.TWOFISH else OpenFailure.Unsupported.Kind.CIPHER)
    is KdbxException.NotEnoughMemory -> unsupported(OpenFailure.Unsupported.Kind.MEMORY)
    is KdbxException.UnsupportedKdf -> unsupported(OpenFailure.Unsupported.Kind.KEY_DERIVATION)
    is KdbxException.UnsupportedInnerStream -> unsupported(OpenFailure.Unsupported.Kind.INNER_STREAM)
}

private fun KdbxException.unsupported(kind: OpenFailure.Unsupported.Kind) = OpenFailure.Unsupported(message ?: "", kind)
