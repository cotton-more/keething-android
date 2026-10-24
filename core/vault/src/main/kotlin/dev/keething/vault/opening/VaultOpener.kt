package dev.keething.vault.opening

import dev.keething.domain.secret.Secret
import dev.keething.domain.storage.DatabaseRead
import dev.keething.domain.storage.DatabaseStore
import dev.keething.domain.storage.FileVersion
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.FieldValue
import dev.keething.domain.vault.Vault
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.kdbx.reader.KdbxDatabase
import dev.keething.kdbx.reader.KdbxException
import dev.keething.kdbx.reader.KdbxReader
import kotlin.time.Duration

/** Why the database could not be opened, in terms the screens can show. Never carries entry data. */
public sealed interface OpenFailure {
    public data object WrongPassword : OpenFailure

    /** No file at the place (Syncthing may be replacing it, SPEC §3.2). */
    public data object Missing : OpenFailure

    /** The file is there but may not be read (the all-files access was revoked). */
    public data object AccessDenied : OpenFailure

    /** A database the app does not read (KDBX 3, Twofish, a key file…): [what]. */
    public data class Unsupported(val what: String) : OpenFailure

    /** Truncated or altered: [detail]. A file caught half-written by a sync is this (SPEC §3.2). */
    public data class Damaged(val detail: String) : OpenFailure

    /** The phone has not enough memory for the key derivation. */
    public data object OutOfMemory : OpenFailure
}

/** A database just opened. */
internal class OpenedVault(
    val vault: Vault,
    val attachments: AttachmentContents,
    /** The version of the file that was read: a newer one is a reason to read it again. */
    val version: FileVersion,
    /** How long the key derivation took; null when the key derived before was used. */
    val keyDerivation: Duration?,
)

internal sealed interface OpenResult {
    class Opened(val opened: OpenedVault) : OpenResult

    class Failed(val failure: OpenFailure) : OpenResult
}

/** Reads the database file and opens it with a key. Blocks: call it off the main thread. */
public class VaultOpener {
    internal fun open(store: DatabaseStore, key: DatabaseKey): OpenResult {
        val found = when (val read = store.read()) {
            is DatabaseRead.Found -> read
            DatabaseRead.Missing -> return OpenResult.Failed(OpenFailure.Missing)
            DatabaseRead.AccessDenied -> return OpenResult.Failed(OpenFailure.AccessDenied)
        }
        val database = try {
            KdbxReader.open(found.bytes, key)
        } catch (failure: KdbxException) {
            return OpenResult.Failed(failure.toOpenFailure())
        } catch (_: OutOfMemoryError) {
            return OpenResult.Failed(OpenFailure.OutOfMemory)
        }
        return OpenResult.Opened(database.toOpenedVault(found.version))
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
        return HistoryResult.Read(EntryVersions(mapper.map(current), document.history.map(mapper::map)))
    }
}

/**
 * An entry as the file has it now ([current]) and its earlier versions, oldest first. Holds secrets: [wipe] it
 * when it is not shown any more.
 */
public class EntryVersions(public val current: Entry, public val history: List<Entry>) {
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
 * The vault of a database read, with its attachments; [version] is the file's that was read. [assembled] is its
 * vault when it was made already (a save's read-back).
 */
internal fun KdbxDatabase.toOpenedVault(version: FileVersion, assembled: Vault? = null): OpenedVault {
    val vault = assembled ?: try {
        VaultAssembler.assemble(document, binaries.map(Secret::size))
    } catch (failure: RuntimeException) {
        binaries.forEach(Secret::wipe)
        throw failure
    }
    val indexes = document.entries.associate { entry ->
        entry.uuid to entry.attachments.mapNotNull { ref -> ref.ref?.let { ref.name to it } }.toMap()
    }
    return OpenedVault(vault, AttachmentContents(binaries, indexes), version, keyDerivation)
}

internal fun KdbxException.toOpenFailure(): OpenFailure = when (this) {
    is KdbxException.WrongPassword -> OpenFailure.WrongPassword
    is KdbxException.Corrupt -> OpenFailure.Damaged(detail)
    is KdbxException.NotKdbx,
    is KdbxException.UnsupportedVersion,
    is KdbxException.UnsupportedCipher,
    is KdbxException.UnsupportedKdf,
    is KdbxException.UnsupportedInnerStream,
    -> OpenFailure.Unsupported(message ?: "")
}
