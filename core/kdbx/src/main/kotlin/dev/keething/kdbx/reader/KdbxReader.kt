package dev.keething.kdbx.reader

import dev.keething.domain.secret.Secret
import dev.keething.domain.vault.FieldValue
import dev.keething.kdbx.crypto.Bytes
import dev.keething.kdbx.crypto.ChaCha20
import dev.keething.kdbx.crypto.KeyStream
import dev.keething.kdbx.editing.EditableDatabase
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.kdbx.key.DerivedKey
import dev.keething.kdbx.tree.XmlTree
import dev.keething.kdbx.xml.KdbxDocument
import dev.keething.kdbx.xml.KdbxXmlReader
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration
import kotlin.time.measureTimedValue

/** A database file opened: its content, its attachments and its format. */
public class KdbxDatabase(
    public val format: KdbxFormat,
    public val document: KdbxDocument,
    /**
     * The attachments in file order (KDBX 4's inner header, KDBX 3.1's `Meta/Binaries`): an entry's attachment
     * refers to the n-th. Wiped on lock.
     */
    public val binaries: List<Secret>,
    /** How long the key derivation took; null when the key derived for an earlier read was used. */
    public val keyDerivation: Duration?,
)

/**
 * Opens a KDBX 4 file (port of the Mac `KDBXReader`): verifies and decrypts it, and reads its XML with the
 * protected values decrypted straight into [Secret]s. A KDBX 3.1 file is opened to be read, never to be edited
 * (keething-spec kdbx.kdbx3). A pure function of its input; reads no files itself.
 */
public object KdbxReader {
    /** Reads only the format, without a key. */
    public fun format(file: ByteArray): KdbxFormat =
        if (isKdbx3(file)) Header3.parse(file).format else Header.parse(file).format

    /** Whether the file is KDBX 3.x, told from its first 12 bytes alone. Throws when it is not a KeePass database. */
    public fun isKdbx3(file: ByteArray): Boolean = Header.version(file).first == 3

    /** [historyOf]: the UUID of an entry whose history items are read too (`document.history`). */
    public fun open(file: ByteArray, key: DatabaseKey, historyOf: String? = null): KdbxDatabase =
        if (isKdbx3(file)) read3(file, key, historyOf) else read(file, { header -> key.transformed(header.kdf) }, historyOf)

    /**
     * Opens a file with [derived] alone, the key a save just wrote it with ([EditableDatabase.readBack]): nothing is
     * looked up or derived; a file with other KDF parameters is not that save's.
     */
    internal fun open(file: ByteArray, derived: DerivedKey): KdbxDatabase = read(file, { header -> derived.forKdf(header.kdf) to false }, null)

    private fun read(file: ByteArray, transform: (Header) -> Pair<ByteArray, Boolean>, historyOf: String?): KdbxDatabase =
        decryptPayload(file, transform) { header, plain, inner, keyDerivation ->
            val stream = streamOf(inner)
            inner.wipeKey()
            try {
                val document = KdbxXmlReader.read(plain, inner.end, plain.size - inner.end, stream, historyOf)
                KdbxDatabase(header.format, document, inner.binaries, keyDerivation)
            } catch (failure: KdbxException) {
                inner.binaries.forEach(Secret::wipe)
                throw failure
            } finally {
                stream.wipe()
            }
        }

    /**
     * Opens a file to change it and write it back (SPEC §3.3; Mac `KDBXReader.openForEditing`): the XML is read
     * twice from the same decrypted bytes, into the document the reader always makes and into a tree that keeps
     * everything. The derived key that has just opened the file, its header's HMAC checked, goes with it: the save
     * writes the new file with that key and reads it back with it (keething-spec kdbx.hardening AC1).
     */
    public fun openForEditing(file: ByteArray, key: DatabaseKey): EditableDatabase {
        var proven: DerivedKey? = null
        try {
            return decryptPayload(file, { header ->
                key.transformed(header.kdf).also { (transformed, _) -> proven = DerivedKey(header.kdf.identity, transformed) }
            }) { header, plain, inner, _ ->
                val documentStream = streamOf(inner)
                val treeStream = streamOf(inner)
                inner.wipeKey()
                var document: KdbxDocument? = null
                try {
                    document = KdbxXmlReader.read(plain, inner.end, plain.size - inner.end, documentStream)
                    val root = XmlTree.parse(plain, inner.end, plain.size - inner.end, treeStream)
                    EditableDatabase(document, root, inner.binaries, inner.binaryFlags, header.template, checkNotNull(proven))
                } catch (failure: KdbxException) {
                    document?.entries?.forEach { entry -> entry.fields.forEach { (it.value as? FieldValue.Protected)?.secret?.wipe() } }
                    inner.binaries.forEach(Secret::wipe)
                    throw failure
                } finally {
                    documentStream.wipe()
                    treeStream.wipe()
                }
            }
        } catch (failure: Throwable) {
            proven?.wipe()
            throw failure
        }
    }

    /**
     * A KDBX 3.1 file (keething-spec kdbx.kdbx3 AC1-AC4): AES-KDF from the header's transform seed and rounds, the
     * payload decrypted, its stream start bytes checked (anything else there is a wrong key: KDBX 3.1 has no header
     * HMAC, so a damaged start reads as one too, as in KeePass), then the HashedBlockStream with each block's hash,
     * gzip, and the XML with its protected values (Salsa20), its `Meta/Binaries` and its `Meta/HeaderHash`.
     */
    private fun read3(file: ByteArray, key: DatabaseKey, historyOf: String?): KdbxDatabase {
        val header = Header3.parse(file)
        try {
            if (header.format.cipher == KdbxFormat.Cipher.TWOFISH) throw KdbxException.UnsupportedCipher("Twofish")
            val (transformedAndFresh, kdfTime) = measureTimedValue { key.transformed(header.kdf) }
            val (transformed, fresh) = transformedAndFresh
            val cipherKey = try {
                sha256(header.masterSeed, transformed)
            } finally {
                transformed.fill(0)
            }
            val plain = try {
                decrypt(file.copyOfRange(header.end, file.size), header.format.cipher, cipherKey, header.iv) { KdbxException.WrongPassword() }
            } finally {
                cipherKey.fill(0)
            }
            var payload = try {
                if (!startsWith(plain, header.streamStartBytes)) throw KdbxException.WrongPassword()
                hashedBlocks(plain, header.streamStartBytes.size)
            } finally {
                plain.fill(0)
            }
            if (header.format.compressed) {
                val unzipped = try {
                    gunzip(payload)
                } finally {
                    payload.fill(0)
                }
                payload = unzipped
            }
            try {
                val stream = InnerStream.of(header.streamId, header.streamKey)
                header.streamKey.fill(0)
                try {
                    val (document, binaries) = KdbxXmlReader.readKdbx3(payload, stream, historyOf, header.sha256)
                    return KdbxDatabase(header.format, document, binaries, kdfTime.takeIf { fresh })
                } finally {
                    stream.wipe()
                }
            } finally {
                payload.fill(0)
            }
        } finally {
            header.streamKey.fill(0)
        }
    }

    private fun startsWith(data: ByteArray, prefix: ByteArray): Boolean {
        if (data.size < prefix.size) return false
        var difference = 0
        for (i in prefix.indices) difference = difference or (data[i].toInt() xor prefix[i].toInt())
        return difference == 0
    }

    /**
     * KDBX 3.1's HashedBlockStream from [start]: blocks of an index, the SHA-256 of the data and its size, in order,
     * up to an empty block with a zero hash. Every block is verified before any of it is used.
     */
    private fun hashedBlocks(data: ByteArray, start: Int): ByteArray {
        val blocks = mutableListOf<IntRange>()
        val r = ByteReader(data, start)
        var index = 0
        while (true) {
            val stored = r.int32()
            val hash = r.bytes(32)
            val size = r.size()
            val from = r.position
            if (stored == null || hash == null || size == null || !r.skip(size)) throw KdbxException.Corrupt("block $index truncated")
            if (stored != index) throw KdbxException.Corrupt("block $index out of order")
            if (size == 0) {
                if (hash.any { it != 0.toByte() }) throw KdbxException.Corrupt("block $index hash")
                break
            }
            val actual = MessageDigest.getInstance("SHA-256").apply { update(data, from, size) }.digest()
            if (!MessageDigest.isEqual(actual, hash)) throw KdbxException.Corrupt("block $index hash")
            blocks += from until from + size
            index++
        }
        return joined(data, blocks)
    }

    /** The inner stream; when it cannot be made, the inner header's key and attachments are wiped first. */
    private fun streamOf(inner: InnerHeader): KeyStream = try {
        inner.stream()
    } catch (failure: KdbxException) {
        inner.wipeKey()
        inner.binaries.forEach(Secret::wipe)
        throw failure
    }

    /**
     * Verifies and decrypts the file, then hands the payload (inner header, then XML) to [read]; the payload is
     * wiped afterwards.
     */
    private fun <T> decryptPayload(
        file: ByteArray,
        transform: (Header) -> Pair<ByteArray, Boolean>,
        read: (header: Header, plain: ByteArray, inner: InnerHeader, keyDerivation: Duration?) -> T,
    ): T {
        val header = Header.parse(file)
        if (header.format.cipher == KdbxFormat.Cipher.TWOFISH) throw KdbxException.UnsupportedCipher("Twofish")

        // The checksum of the header first: a damaged file is not a wrong password.
        val sha256 = MessageDigest.getInstance("SHA-256").apply { update(file, 0, header.checksumStart) }.digest()
        if (!MessageDigest.isEqual(sha256, header.sha256)) throw KdbxException.Corrupt("header checksum")

        val (transformedAndFresh, kdfTime) = measureTimedValue { transform(header) }
        val (transformed, fresh) = transformedAndFresh
        val cipherKey: ByteArray
        val hmacBase: ByteArray
        try {
            cipherKey = sha256(header.masterSeed, transformed)
            hmacBase = sha512(header.masterSeed, transformed, byteArrayOf(1))
        } finally {
            transformed.fill(0)
        }
        try {
            val headerMac = hmac(blockKey(-1L, hmacBase)) { update(file, 0, header.checksumStart) }
            if (!MessageDigest.isEqual(headerMac, header.hmac)) throw KdbxException.WrongPassword()

            val encrypted = readBlocks(file, header.end, hmacBase)
            var plain = decrypt(encrypted, header.format.cipher, cipherKey, header.iv)
            if (header.format.compressed) {
                val unzipped = try {
                    gunzip(plain)
                } finally {
                    plain.fill(0)
                }
                plain = unzipped
            }
            try {
                val inner = InnerHeader.parse(plain, plain.size)
                return read(header, plain, inner, kdfTime.takeIf { fresh })
            } finally {
                plain.fill(0)
            }
        } finally {
            cipherKey.fill(0)
            hmacBase.fill(0)
        }
    }

    // Keys

    private fun blockKey(index: Long, base: ByteArray): ByteArray = sha512(Bytes.le64(index), base)

    // Payload

    /** The HMAC block stream: every block is verified before any of it is used. */
    private fun readBlocks(file: ByteArray, start: Int, base: ByteArray): ByteArray {
        val blocks = mutableListOf<IntRange>()
        val r = ByteReader(file, start)
        var index = 0L
        while (true) {
            val mac = r.bytes(32)
            val size = r.size()
            val from = r.position
            if (mac == null || size == null || !r.skip(size)) throw KdbxException.Corrupt("block $index truncated")
            val key = blockKey(index, base)
            val expected = hmac(key) {
                update(Bytes.le64(index))
                update(Bytes.le32(size))
                update(file, from, size)
            }
            key.fill(0)
            if (!MessageDigest.isEqual(expected, mac)) throw KdbxException.Corrupt("block $index HMAC")
            if (size == 0) break
            blocks += from until from + size
            index++
        }
        return joined(file, blocks)
    }

    private fun joined(data: ByteArray, blocks: List<IntRange>): ByteArray {
        val out = ByteArray(blocks.sumOf { it.last - it.first + 1 })
        var offset = 0
        for (block in blocks) {
            data.copyInto(out, offset, block.first, block.last + 1)
            offset += block.last - block.first + 1
        }
        return out
    }

    /** [badPadding]: what AES's padding not checking out means (in KDBX 3.1, with no HMAC before it, a wrong key). */
    private fun decrypt(
        data: ByteArray,
        cipher: KdbxFormat.Cipher,
        key: ByteArray,
        iv: ByteArray,
        badPadding: () -> KdbxException = { KdbxException.Corrupt("AES decryption") },
    ): ByteArray =
        when (cipher) {
            KdbxFormat.Cipher.CHACHA20 -> {
                if (iv.size != 12) throw KdbxException.Corrupt("ChaCha20 IV")
                val stream = ChaCha20(key, iv)
                stream.apply(data)
                stream.wipe()
                data
            }
            KdbxFormat.Cipher.AES256 -> {
                if (iv.size != 16) throw KdbxException.Corrupt("AES IV")
                try {
                    Cipher.getInstance("AES/CBC/PKCS5Padding")
                        .apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }
                        .doFinal(data)
                } catch (_: GeneralSecurityException) {
                    throw badPadding()
                }
            }
            KdbxFormat.Cipher.TWOFISH -> throw KdbxException.UnsupportedCipher("Twofish")
        }

    /** One allocation of the size the gzip trailer gives, so that no stray copies of the XML are left. */
    internal fun gunzip(data: ByteArray): ByteArray {
        if (data.size < 18) throw KdbxException.Corrupt("gzip")
        val size = Bytes.intLe(data, data.size - 4)
        if (size < 0) throw KdbxException.Corrupt("gzip")
        val out = ByteArray(size)
        try {
            GZIPInputStream(ByteArrayInputStream(data), 64 * 1024).use { input ->
                var read = 0
                while (read < size) {
                    val n = input.read(out, read, size - read)
                    if (n < 0) throw KdbxException.Corrupt("gzip")
                    read += n
                }
                if (input.read() != -1) throw KdbxException.Corrupt("gzip")
            }
        } catch (_: IOException) {
            out.fill(0)
            throw KdbxException.Corrupt("gzip")
        } catch (failure: KdbxException) {
            out.fill(0)
            throw failure
        }
        return out
    }

    // Helpers

    private fun sha256(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").apply { parts.forEach(::update) }.digest()

    private fun sha512(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-512").apply { parts.forEach(::update) }.digest()

    private inline fun hmac(key: ByteArray, feed: Mac.() -> Unit): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")); feed() }.doFinal()
}
