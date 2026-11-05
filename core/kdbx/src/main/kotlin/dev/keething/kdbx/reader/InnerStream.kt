package dev.keething.kdbx.reader

import dev.keething.kdbx.crypto.ChaCha20
import dev.keething.kdbx.crypto.KeyStream
import dev.keething.kdbx.crypto.Salsa20
import java.security.MessageDigest

/** The stream that hides protected values, from the file's stream id and key (KeePass's `CrsAlgorithm`). */
internal object InnerStream {
    /** KDBX 3.1's stream. */
    const val SALSA20 = 2

    /** KDBX 4's stream. */
    const val CHACHA20 = 3

    /** KeePass's fixed Salsa20 nonce for the inner stream. */
    private val SALSA20_NONCE = byteArrayOf(0xE8.toByte(), 0x30, 0x09, 0x4B, 0x97.toByte(), 0x20, 0x5D, 0x2A)

    /** Salsa20 keyed with the SHA-256 of [key]; ChaCha20 keyed with the SHA-512 of it. [key] stays the caller's to wipe. */
    fun of(id: Int, key: ByteArray): KeyStream = when (id) {
        SALSA20 -> {
            val hash = MessageDigest.getInstance("SHA-256").digest(key)
            try {
                Salsa20(hash, SALSA20_NONCE)
            } finally {
                hash.fill(0)
            }
        }
        CHACHA20 -> {
            val hash = MessageDigest.getInstance("SHA-512").digest(key)
            try {
                ChaCha20(hash.copyOfRange(0, 32), hash.copyOfRange(32, 44))
            } finally {
                hash.fill(0)
            }
        }
        else -> throw KdbxException.UnsupportedInnerStream(id)
    }
}
