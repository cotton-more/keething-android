package dev.keething.kdbx.reader

/** What a file says about its own format; nothing secret. */
public data class KdbxFormat(
    val version: String,
    val cipher: Cipher,
    val kdf: Kdf,
    val compressed: Boolean,
) {
    public enum class Cipher(public val label: String) { AES256("AES-256"), CHACHA20("ChaCha20"), TWOFISH("Twofish") }

    public sealed interface Kdf {
        public data class AesKdf(val rounds: Long) : Kdf

        public data class Argon2(
            val variant: Variant,
            val iterations: Long,
            val memoryBytes: Long,
            val parallelism: Int,
        ) : Kdf {
            public enum class Variant { D, ID }
        }
    }
}
