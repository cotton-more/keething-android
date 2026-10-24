package dev.keething.kdbx.reader

/** Why a file could not be opened (Mac `KDBXReader.Failure`). Messages never quote the file's content. */
public sealed class KdbxException(message: String) : Exception(message) {
    public class NotKdbx : KdbxException("not a KeePass database")

    /** KDBX 3.1 and older are not read. */
    public class UnsupportedVersion(public val major: Int, public val minor: Int) :
        KdbxException("KDBX $major.$minor")

    public class UnsupportedCipher(public val cipher: String) : KdbxException("cipher $cipher")

    public class UnsupportedKdf(public val kdf: String) : KdbxException("key derivation $kdf")

    public class UnsupportedInnerStream(public val id: Int) : KdbxException("inner stream $id")

    /** The header's HMAC does not match: a wrong password (or a key file is needed). */
    public class WrongPassword : KdbxException("wrong password")

    /**
     * Truncated or altered: a checksum or an HMAC does not match, or a structure is broken. A file caught
     * half-written by a sync is this, not a wrong password.
     */
    public class Corrupt(public val detail: String) : KdbxException("damaged: $detail")
}
