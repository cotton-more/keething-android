package dev.keething.kdbx.key

import dev.keething.kdbx.reader.KdbxException

/**
 * The key derived for one file's KDF parameters ([identity]) that has just opened that file, its header's HMAC checked
 * (keething-spec kdbx.hardening AC1): a save writes the new file with it and reads that back with it, so nothing in
 * between looks a key up or derives one. A copy of its own; [wipe] when the save is done. Used wiped, it throws a
 * [WipedKeyException] (AC2).
 */
internal class DerivedKey(identity: ByteArray, transformed: ByteArray) {
    private val identity = identity.copyOf()
    private val bytes = transformed.copyOf()
    private var wiped = false

    /** A copy of the key for a file with [kdf] (the caller wipes it); a file of other KDF parameters is not its file. */
    fun forKdf(kdf: KdfParameters): ByteArray = synchronized(this) {
        if (wiped) throw WipedKeyException()
        if (!kdf.identity.contentEquals(identity)) throw KdbxException.Corrupt("the key derivation's parameters changed")
        bytes.copyOf()
    }

    fun wipe(): Unit = synchronized(this) {
        bytes.fill(0)
        wiped = true
    }
}
