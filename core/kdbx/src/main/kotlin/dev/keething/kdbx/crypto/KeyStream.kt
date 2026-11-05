package dev.keething.kdbx.crypto

/** A stream cipher's keystream: what hides protected values in the XML (ChaCha20 in KDBX 4, Salsa20 in KDBX 3.1). */
internal interface KeyStream {
    /** XORs the keystream into [bytes], continuing where the last call stopped. */
    fun apply(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size - offset)

    fun wipe()
}
