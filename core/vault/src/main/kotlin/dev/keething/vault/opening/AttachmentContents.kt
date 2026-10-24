package dev.keething.vault.opening

import dev.keething.domain.secret.Secret

/**
 * The attachments of the open database, kept in memory while it is unlocked (SPEC §4): opening one needs no
 * second decryption. Wiped on lock (Mac `AttachmentContents`).
 */
internal class AttachmentContents(
    private var binaries: List<Secret>,
    /** Entry id → attachment name → index into [binaries]. */
    private val indexes: Map<String, Map<String, Int>>,
) {
    /** A copy of one attachment's bytes (the caller wipes it); null when there is no such attachment. */
    @Synchronized
    fun bytes(entryId: String, name: String): Secret? =
        indexes[entryId]?.get(name)?.let(binaries::getOrNull)?.copy()

    @Synchronized
    fun wipe() {
        binaries.forEach(Secret::wipe)
        binaries = emptyList()
    }
}
