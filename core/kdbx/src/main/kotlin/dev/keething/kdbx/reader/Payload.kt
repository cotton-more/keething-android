package dev.keething.kdbx.reader

import dev.keething.kdbx.crypto.Bytes
import dev.keething.kdbx.crypto.ChaCha20
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** What went wrong under a stream of the payload, carried up through the [IOException] a stream may throw. */
internal class PayloadFailure(val failure: KdbxException) : IOException(failure.message)

/**
 * The file could not be read (the storage's failure, not the file's): carried up through the payload's streams and the
 * XML parser, then thrown as [failure] itself, never as a damaged file.
 */
internal class SourceFailure(val failure: IOException) : IOException(failure.message, failure)

/**
 * A KDBX 4 file's payload (the inner header, then the XML) as it is read, never whole in memory: the HMAC blocks, each
 * checked before any of its bytes is used; decrypted as they come; gunzipped when the file is compressed. [finish]
 * reads what is left to the end, so that every block, the padding and gzip's checksum are checked as before; [close]
 * wipes the plain bytes held. A failure is a [KdbxException] (in [input]'s reads, a [PayloadFailure] carrying one).
 */
internal class Payload private constructor(
    private val blocks: BlockInput,
    private val decrypted: DecryptingInput,
    private val gunzipped: GunzipInput?,
) : AutoCloseable {
    /** The payload's plain bytes. */
    val input: InputStream get() = gunzipped ?: decrypted

    /** Reads the payload to its end: the bytes past the XML are not looked at, only checked. */
    fun finish() {
        carrying {
            val scratch = ByteArray(64 * 1024)
            try {
                gunzipped?.let { while (it.read(scratch) >= 0) Unit }
                while (decrypted.read(scratch) >= 0) Unit
            } finally {
                scratch.fill(0)
            }
            check(blocks.ended)
        }
    }

    override fun close() {
        gunzipped?.wipe()
        decrypted.wipe()
    }

    companion object {
        /** [source] past the header. */
        fun open(source: InputStream, header: Header, cipherKey: ByteArray, hmacBase: ByteArray): Payload {
            val blocks = BlockInput(source, hmacBase)
            val decrypted = DecryptingInput(blocks, header.format.cipher, cipherKey, header.iv)
            val gunzipped = try {
                if (header.format.compressed) carrying { GunzipInput(decrypted) } else null
            } catch (failure: KdbxException) {
                decrypted.wipe()
                throw failure
            }
            return Payload(blocks, decrypted, gunzipped)
        }

        /** [block], its [PayloadFailure] thrown as the [KdbxException] it carries, a [SourceFailure] as its [IOException]. */
        inline fun <T> carrying(block: () -> T): T = try {
            block()
        } catch (failure: PayloadFailure) {
            throw failure.failure
        } catch (failure: SourceFailure) {
            throw failure.failure
        }
    }
}

/**
 * The HMAC block stream read from [source], as the bytes of its blocks: no block is given out before its HMAC is checked.
 * A block is held whole for that (KeePass writes them of 1 MiB), in one buffer kept for the next; a size larger than
 * what follows is not allocated ahead, only read as far as it goes. A failure of [source] is a [SourceFailure].
 */
internal class BlockInput(private val source: InputStream, private val hmacBase: ByteArray) : InputStream() {
    private var block = ByteArray(0)
    private var index = 0L
    private var at = 0
    private var left = 0

    /** The empty block that ends the stream was read. */
    var ended = false
        private set

    override fun read(): Int {
        if (left == 0 && !next()) return -1
        left--
        return block[at++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (left == 0 && !next()) return -1
        val n = minOf(len, left)
        block.copyInto(b, off, at, at + n)
        at += n
        left -= n
        return n
    }

    private fun next(): Boolean {
        if (ended) return false
        val truncated = PayloadFailure(KdbxException.Corrupt("block $index truncated"))
        val mac = fromSource { source.readNBytes(32) }
        val sizeField = fromSource { source.readNBytes(4) }
        if (mac.size < 32 || sizeField.size < 4) throw truncated
        val size = Bytes.intLe(sizeField, 0)
        if (size < 0) throw truncated
        if (size <= block.size) {
            if (fromSource { source.readNBytes(block, 0, size) } < size) throw truncated
        } else {
            block = fromSource { source.readNBytes(size) }
            if (block.size < size) throw truncated
        }
        val key = MessageDigest.getInstance("SHA-512").apply { update(Bytes.le64(index)); update(hmacBase) }.digest()
        val expected = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            update(Bytes.le64(index))
            update(sizeField)
            update(block, 0, size)
            doFinal()
        }
        key.fill(0)
        if (!MessageDigest.isEqual(expected, mac)) throw PayloadFailure(KdbxException.Corrupt("block $index HMAC"))
        if (size == 0) {
            ended = true
            return false
        }
        at = 0
        left = size
        index++
        return true
    }

    private inline fun <T> fromSource(read: () -> T): T = try {
        read()
    } catch (failure: IOException) {
        throw SourceFailure(failure)
    }
}

/** The blocks' bytes decrypted as they are read: AES-256 in CBC mode (its padding checked at the end) or ChaCha20. */
internal class DecryptingInput(
    private val source: InputStream,
    cipher: KdbxFormat.Cipher,
    key: ByteArray,
    iv: ByteArray,
) : InputStream() {
    private val chaCha: ChaCha20?
    private val aes: Cipher?
    private val encrypted: ByteArray
    private val plain: ByteArray
    private var at = 0
    private var left = 0
    private var ended = false

    init {
        when (cipher) {
            KdbxFormat.Cipher.CHACHA20 -> {
                if (iv.size != 12) throw KdbxException.Corrupt("ChaCha20 IV")
                chaCha = ChaCha20(key, iv)
                aes = null
            }
            KdbxFormat.Cipher.AES256 -> {
                if (iv.size != 16) throw KdbxException.Corrupt("AES IV")
                chaCha = null
                aes = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }
            }
            KdbxFormat.Cipher.TWOFISH -> throw KdbxException.UnsupportedCipher("Twofish")
        }
        encrypted = ByteArray(if (aes != null) BUFFER else 0)
        plain = ByteArray(if (aes != null) BUFFER + 16 else 0)
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        chaCha?.let { stream ->
            val n = source.read(b, off, len)
            if (n > 0) stream.apply(b, off, n)
            return n
        }
        val aes = checkNotNull(aes)
        while (left == 0) {
            if (ended) return -1
            val n = source.read(encrypted)
            try {
                left = if (n < 0) aes.doFinal(plain, 0).also { ended = true } else aes.update(encrypted, 0, n, plain)
            } catch (_: GeneralSecurityException) {
                throw PayloadFailure(KdbxException.Corrupt("AES decryption"))
            }
            at = 0
        }
        val n = minOf(len, left)
        plain.copyInto(b, off, at, at + n)
        at += n
        left -= n
        return n
    }

    fun wipe() {
        chaCha?.wipe()
        plain.fill(0)
    }

    private companion object {
        const val BUFFER = 64 * 1024
    }
}

/** The decrypted payload gunzipped; gzip's own failures are [KdbxException.Corrupt], and its buffer is wiped at the end. */
internal class GunzipInput(source: InputStream) : InputStream() {
    private val gzip = try {
        WipingGzip(source)
    } catch (failure: PayloadFailure) {
        throw failure
    } catch (failure: SourceFailure) {
        throw failure
    } catch (_: IOException) {
        throw PayloadFailure(KdbxException.Corrupt("gzip"))
    }

    override fun read(): Int = guarded { gzip.read() }

    override fun read(b: ByteArray, off: Int, len: Int): Int = guarded { gzip.read(b, off, len) }

    fun wipe() {
        gzip.wipe()
    }

    private inline fun guarded(read: () -> Int): Int = try {
        read()
    } catch (failure: PayloadFailure) {
        throw failure
    } catch (failure: SourceFailure) {
        throw failure
    } catch (_: EOFException) {
        throw PayloadFailure(KdbxException.Corrupt("gzip"))
    } catch (_: IOException) {
        throw PayloadFailure(KdbxException.Corrupt("gzip"))
    }

    private class WipingGzip(source: InputStream) : GZIPInputStream(source, 64 * 1024) {
        fun wipe() {
            buf.fill(0)
            inf.end()
        }
    }
}
