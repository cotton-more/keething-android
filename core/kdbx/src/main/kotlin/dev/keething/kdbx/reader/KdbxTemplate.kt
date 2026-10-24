package dev.keething.kdbx.reader

import dev.keething.kdbx.key.KdfParameters

/**
 * The format of an opened file, to write it again the same way (SPEC §3.3; Mac `KDBXTemplate`): version, cipher,
 * compression, the key derivation with its parameters and salt, public custom data. Nothing secret.
 */
internal data class KdbxTemplate(
    val major: Int,
    val minor: Int,
    val cipherId: ByteArray,
    val cipher: KdbxFormat.Cipher,
    val compressed: Boolean,
    /** Written back unchanged, salt included: the key derived for the file keeps opening it (SPEC §4). */
    val kdf: KdfParameters,
    val publicCustomData: ByteArray?,
)
