package dev.keething.kdbx.reader

/** Why a file could not be opened (Mac `KDBXReader.Failure`). Messages never quote the file's content. */
public sealed class KdbxException(message: String) : Exception(message) {
    public class NotKdbx : KdbxException("not a KeePass database")

    /** KDBX 2 and older (and any version after 4) are not read; KDBX 3.x is read but not edited (keething-spec kdbx.kdbx3). */
    public class UnsupportedVersion(public val major: Int, public val minor: Int) :
        KdbxException("KDBX $major.$minor")

    public class UnsupportedCipher(public val cipher: String) : KdbxException("cipher $cipher")

    public open class UnsupportedKdf(public val kdf: String) : KdbxException("key derivation $kdf")

    /**
     * Argon2's memory is within KeePass's limits but more than the phone gives the app (keething-spec kdbx.hardening
     * AC6): refused before it is allocated, never run out of. [kdf] names it.
     */
    public class NotEnoughMemory(kdf: String) : UnsupportedKdf(kdf)

    public class UnsupportedInnerStream(public val id: Int) : KdbxException("inner stream $id")

    /** The header's HMAC does not match: a wrong password (or a key file is needed). */
    public class WrongPassword : KdbxException("wrong password")

    /**
     * Truncated or altered: a checksum or an HMAC does not match, or a structure is broken. A file caught
     * half-written by a sync is this, not a wrong password.
     */
    public class Corrupt(public val detail: String) : KdbxException("damaged: $detail")
}
