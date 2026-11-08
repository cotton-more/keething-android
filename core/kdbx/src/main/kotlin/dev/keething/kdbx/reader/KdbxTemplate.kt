package dev.keething.kdbx.reader

import dev.keething.kdbx.key.KdfParameters
import java.security.SecureRandom

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
) {
    /**
     * The same, with a new 32-byte salt from [random] for its key derivation (AES-KDF: its seed), the costs and every
     * other parameter as they were (keething-spec databases.change-master-key AC4): a new master key gets a derivation
     * of its own.
     */
    fun withNewSalt(random: SecureRandom): KdbxTemplate {
        val salt = ByteArray(32).also(random::nextBytes)
        val parameters = VariantDictionary.parse(kdf.identity).replacingBytes("S", salt)
        return copy(kdf = Header.kdf(parameters).first)
    }
}
