package dev.keepassbar.kdbx.reader

import dev.keepassbar.domain.secret.Secret
import dev.keepassbar.kdbx.crypto.Bytes
import dev.keepassbar.kdbx.crypto.ChaCha20
import dev.keepassbar.kdbx.key.DatabaseKey
import dev.keepassbar.kdbx.xml.KdbxDocument
import dev.keepassbar.kdbx.xml.KdbxXmlReader
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
    /** Inner-header attachments in file order: `<Value Ref="n"/>` refers to the n-th. Wiped on lock. */
    public val binaries: List<Secret>,
    /** How long the key derivation took; null when the key derived for an earlier read was used. */
    public val keyDerivation: Duration?,
)

/**
 * Opens a KDBX 4 file (port of the Mac `KDBXReader`): verifies and decrypts it, and reads its XML with the
 * protected values decrypted straight into [Secret]s. A pure function of its input; reads no files itself.
 */
public object KdbxReader {
    /** Reads only the format, without a key. */
    public fun format(file: ByteArray): KdbxFormat = Header.parse(file).format

    public fun open(file: ByteArray, key: DatabaseKey): KdbxDatabase {
        val header = Header.parse(file)
        if (header.format.cipher == KdbxFormat.Cipher.TWOFISH) throw KdbxException.UnsupportedCipher("Twofish")

        // The checksum of the header first: a damaged file is not a wrong password.
        val sha256 = MessageDigest.getInstance("SHA-256").apply { update(file, 0, header.checksumStart) }.digest()
        if (!MessageDigest.isEqual(sha256, header.sha256)) throw KdbxException.Corrupt("header checksum")

        val (transformedAndFresh, kdfTime) = measureTimedValue { key.transformed(header.kdf) }
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
                val stream = inner.stream()
                inner.wipeKey()
                try {
                    val document = KdbxXmlReader.read(plain, inner.end, plain.size - inner.end, stream)
                    return KdbxDatabase(header.format, document, inner.binaries, kdfTime.takeIf { fresh })
                } catch (failure: KdbxException) {
                    inner.binaries.forEach(Secret::wipe)
                    throw failure
                } finally {
                    stream.wipe()
                }
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
        val out = ByteArray(blocks.sumOf { it.last - it.first + 1 })
        var offset = 0
        for (block in blocks) {
            file.copyInto(out, offset, block.first, block.last + 1)
            offset += block.last - block.first + 1
        }
        return out
    }

    private fun decrypt(data: ByteArray, cipher: KdbxFormat.Cipher, key: ByteArray, iv: ByteArray): ByteArray =
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
                    throw KdbxException.Corrupt("AES decryption")
                }
            }
            KdbxFormat.Cipher.TWOFISH -> throw KdbxException.UnsupportedCipher("Twofish")
        }

    /** One allocation of the size the gzip trailer gives, so that no stray copies of the XML are left. */
    private fun gunzip(data: ByteArray): ByteArray {
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
