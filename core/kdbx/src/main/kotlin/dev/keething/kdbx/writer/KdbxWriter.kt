package dev.keething.kdbx.writer

import dev.keething.domain.secret.Secret
import dev.keething.kdbx.crypto.Bytes
import dev.keething.kdbx.crypto.ChaCha20
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.kdbx.reader.KdbxException
import dev.keething.kdbx.reader.KdbxFormat
import dev.keething.kdbx.reader.KdbxTemplate
import dev.keething.kdbx.tree.XElement
import dev.keething.kdbx.tree.XmlTree
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Writes a KDBX 4 file (SPEC §3.3; port of the Mac `KDBXWriter`): the format of the file it was read from
 * (version, raised from 4.0 to 4.1 only when the document needs it; cipher, compression, the key derivation
 * with its parameters and salt), with a new master seed, IV and inner stream key, as KeePassXC does on every
 * save. Keeping the salt keeps the derived key, so a save derives nothing. The attachments go back with their
 * flags, in their order, so the entries' `Ref`s stay right.
 */
internal object KdbxWriter {
    private const val SIGNATURE_1 = 0x9AA2D903.toInt()
    private const val SIGNATURE_2 = 0xB54BFB67.toInt()
    private const val BLOCK_SIZE = 1 shl 20

    fun write(
        root: XElement,
        binaries: List<Secret>,
        binaryFlags: List<Byte>,
        template: KdbxTemplate,
        key: DatabaseKey,
        random: SecureRandom = SecureRandom(),
    ): ByteArray {
        if (template.cipher == KdbxFormat.Cipher.TWOFISH) throw KdbxException.UnsupportedCipher("Twofish")
        require(binaryFlags.size == binaries.size) { "a flag for every attachment" }

        val masterSeed = ByteArray(32).also(random::nextBytes)
        val iv = ByteArray(if (template.cipher == KdbxFormat.Cipher.CHACHA20) 12 else 16).also(random::nextBytes)
        val streamKey = ByteArray(64).also(random::nextBytes)
        try {
            val header = header(template, masterSeed, iv, minor = maxOf(template.minor, requiredMinor(root)))
            val (transformed, _) = key.transformed(template.kdf)
            val cipherKey: ByteArray
            val hmacBase: ByteArray
            try {
                cipherKey = sha256(masterSeed, transformed)
                hmacBase = sha512(masterSeed, transformed, byteArrayOf(1))
            } finally {
                transformed.fill(0)
            }
            try {
                val payload = WipeableBuffer(binaries.sumOf { it.size } + (1 shl 20))
                try {
                    writePayload(payload, root, binaries, binaryFlags, streamKey, template.compressed)
                    val (encrypted, length) = encrypt(payload, template.cipher, cipherKey, iv)
                    return assemble(header, encrypted, length, hmacBase)
                } finally {
                    payload.wipe()
                }
            } finally {
                cipherKey.fill(0)
                hmacBase.fill(0)
            }
        } finally {
            streamKey.fill(0)
        }
    }

    /**
     * KDBX 4.1 when the document uses what only 4.1 has; of that, the app itself writes only
     * `PreviousParentGroup` (an entry in the recycle bin). KeePassXC raises the version for it the same way.
     */
    fun requiredMinor(root: XElement): Int {
        fun uses41(element: XElement): Boolean = element.elements.any { it.name == "PreviousParentGroup" || uses41(it) }
        return if (root.first("Root")?.let(::uses41) == true) 1 else 0
    }

    private fun header(template: KdbxTemplate, masterSeed: ByteArray, iv: ByteArray, minor: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(Bytes.le32(SIGNATURE_1))
        out.write(Bytes.le32(SIGNATURE_2))
        out.write(le16(minor))
        out.write(le16(template.major))
        fun field(id: Int, value: ByteArray) {
            out.write(id)
            out.write(Bytes.le32(value.size))
            out.write(value)
        }
        field(2, template.cipherId)
        field(3, Bytes.le32(if (template.compressed) 1 else 0))
        field(4, masterSeed)
        field(7, iv)
        field(11, template.kdf.identity)
        template.publicCustomData?.let { field(12, it) }
        field(0, byteArrayOf(0x0D, 0x0A, 0x0D, 0x0A))
        return out.toByteArray()
    }

    /** The inner header, then the XML with the protected values sealed by the new inner stream; gzipped when asked. */
    private fun writePayload(
        payload: WipeableBuffer,
        root: XElement,
        binaries: List<Secret>,
        binaryFlags: List<Byte>,
        streamKey: ByteArray,
        compressed: Boolean,
    ) {
        val gzip = if (compressed) WipingGzip(payload) else null
        val sink: OutputStream = gzip ?: payload
        try {
            fun field(id: Int, value: ByteArray) {
                sink.write(id)
                sink.write(Bytes.le32(value.size))
                sink.write(value)
            }
            field(1, Bytes.le32(3))
            field(2, streamKey)
            binaries.forEachIndexed { index, binary ->
                binary.useBytes { bytes ->
                    sink.write(3)
                    sink.write(Bytes.le32(bytes.size + 1))
                    sink.write(binaryFlags[index].toInt())
                    sink.write(bytes)
                }
            }
            field(0, ByteArray(0))

            val hash = MessageDigest.getInstance("SHA-512").digest(streamKey)
            val stream = ChaCha20(hash.copyOfRange(0, 32), hash.copyOfRange(32, 44))
            hash.fill(0)
            try {
                XmlTree.write(root, sink) { secret -> XmlTree.seal(secret, stream) }
            } finally {
                stream.wipe()
            }
            gzip?.finish()
        } finally {
            gzip?.end()
        }
    }

    /** Encrypts the payload, ChaCha20 in place, AES into a new array: the encrypted bytes and their length. */
    private fun encrypt(payload: WipeableBuffer, cipher: KdbxFormat.Cipher, key: ByteArray, iv: ByteArray): Pair<ByteArray, Int> =
        when (cipher) {
            KdbxFormat.Cipher.CHACHA20 -> {
                val stream = ChaCha20(key, iv)
                stream.apply(payload.bytes, 0, payload.size)
                stream.wipe()
                payload.bytes to payload.size
            }
            KdbxFormat.Cipher.AES256 ->
                Cipher.getInstance("AES/CBC/PKCS5Padding")
                    .apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }
                    .doFinal(payload.bytes, 0, payload.size)
                    .let { it to it.size }
            KdbxFormat.Cipher.TWOFISH -> throw KdbxException.UnsupportedCipher("Twofish")
        }

    /** The header, its SHA-256 and HMAC, then the HMAC block stream: 1 MiB blocks and an empty last one. */
    private fun assemble(header: ByteArray, encrypted: ByteArray, length: Int, hmacBase: ByteArray): ByteArray {
        val blocks = (length + BLOCK_SIZE - 1) / BLOCK_SIZE
        val file = ByteArray(header.size + 64 + (blocks + 1) * 36 + length)
        header.copyInto(file)
        var at = header.size
        MessageDigest.getInstance("SHA-256").digest(header).copyInto(file, at)
        at += 32
        hmac(blockKey(-1L, hmacBase)) { update(header) }.copyInto(file, at)
        at += 32
        var offset = 0
        for (index in 0L..blocks.toLong()) {
            val size = minOf(BLOCK_SIZE, length - offset)
            val key = blockKey(index, hmacBase)
            hmac(key) {
                update(Bytes.le64(index))
                update(Bytes.le32(size))
                update(encrypted, offset, size)
            }.copyInto(file, at)
            key.fill(0)
            Bytes.putIntLe(file, at + 32, size)
            encrypted.copyInto(file, at + 36, offset, offset + size)
            at += 36 + size
            offset += size
        }
        check(at == file.size)
        return file
    }

    private fun blockKey(index: Long, base: ByteArray): ByteArray = sha512(Bytes.le64(index), base)

    private fun le16(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte())

    private fun sha256(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").apply { parts.forEach(::update) }.digest()

    private fun sha512(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-512").apply { parts.forEach(::update) }.digest()

    private inline fun hmac(key: ByteArray, feed: Mac.() -> Unit): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")); feed() }.doFinal()

    /** Gzip whose own buffer, compressed plain text, is wiped at the end. */
    private class WipingGzip(out: OutputStream) : GZIPOutputStream(out, 64 * 1024) {
        fun end() {
            buf.fill(0)
            def.end()
        }
    }
}

/** A growing byte buffer for the plain payload: every array it leaves behind is wiped, and so is the last. */
internal class WipeableBuffer(capacity: Int) : OutputStream() {
    var bytes = ByteArray(maxOf(capacity, 16))
        private set
    var size = 0
        private set

    override fun write(b: Int) {
        ensure(1)
        bytes[size++] = b.toByte()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        ensure(len)
        b.copyInto(bytes, size, off, off + len)
        size += len
    }

    fun wipe() {
        bytes.fill(0)
        size = 0
    }

    private fun ensure(more: Int) {
        if (size + more <= bytes.size) return
        val grown = bytes.copyOf(maxOf(bytes.size * 2, size + more))
        bytes.fill(0)
        bytes = grown
    }
}
