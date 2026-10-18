package dev.keepassbar.vault.opening

import dev.keepassbar.domain.secret.Secret
import dev.keepassbar.domain.storage.DatabaseRead
import dev.keepassbar.domain.storage.DatabaseStore
import dev.keepassbar.domain.storage.FileVersion
import dev.keepassbar.domain.vault.Vault
import dev.keepassbar.kdbx.key.DatabaseKey
import dev.keepassbar.kdbx.reader.KdbxException
import dev.keepassbar.kdbx.reader.KdbxReader
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
        val vault = try {
            VaultAssembler.assemble(database.document, database.binaries.map(Secret::size))
        } catch (failure: RuntimeException) {
            database.binaries.forEach(Secret::wipe)
            throw failure
        }
        val indexes = database.document.entries.associate { entry ->
            entry.uuid to entry.attachments.mapNotNull { ref -> ref.ref?.let { ref.name to it } }.toMap()
        }
        return OpenResult.Opened(
            OpenedVault(vault, AttachmentContents(database.binaries, indexes), found.version, database.keyDerivation),
        )
    }

    private fun KdbxException.toOpenFailure(): OpenFailure = when (this) {
        is KdbxException.WrongPassword -> OpenFailure.WrongPassword
        is KdbxException.Corrupt -> OpenFailure.Damaged(detail)
        is KdbxException.NotKdbx,
        is KdbxException.UnsupportedVersion,
        is KdbxException.UnsupportedCipher,
        is KdbxException.UnsupportedKdf,
        is KdbxException.UnsupportedInnerStream,
        -> OpenFailure.Unsupported(message ?: "")
    }
}
