package dev.keething.kdbx.writer

import dev.keething.domain.secret.Secret
import dev.keething.kdbx.crypto.Bytes
import dev.keething.kdbx.crypto.ChaCha20
import dev.keething.kdbx.editing.EntryLifecycle
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.kdbx.key.DerivedKey
import dev.keething.kdbx.reader.KdbxException
import dev.keething.kdbx.reader.KdbxFormat
import dev.keething.kdbx.reader.KdbxTemplate
import dev.keething.kdbx.schema.KdbxSchema
import dev.keething.kdbx.tree.XElement
import dev.keething.kdbx.tree.XmlTree
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Writes a KDBX 4 file (SPEC §3.3; port of the Mac `KDBXWriter`): the format of the file it was read from
 * (version, raised from 4.0 to 4.1 only when the document needs it; cipher, compression, the key derivation
 * with its parameters and salt), with a new master seed, IV and inner stream key, as KeePassXC does on every
 * save. Keeping the salt keeps the derived key, so a save derives nothing: it writes with the key that opened the
 * file ([DerivedKey], keething-spec kdbx.hardening AC1). The attachments go back with their flags, in their order, so
 * the entries' `Ref`s stay right.
 */
internal object KdbxWriter {
    private const val SIGNATURE_1 = 0x9AA2D903.toInt()
    private const val SIGNATURE_2 = 0xB54BFB67.toInt()
    private const val BLOCK_SIZE = 1 shl 20

    /** Sealed with [key], derived for [template]'s KDF parameters: a new database, or a new master key. */
    fun write(
        root: XElement,
        binaries: List<Secret>,
        binaryFlags: List<Byte>,
        template: KdbxTemplate,
        key: DatabaseKey,
        random: SecureRandom = SecureRandom(),
    ): ByteArray {
        val payload = payload(root, binaries, binaryFlags, template, random)
        try {
            return seal(payload, key)
        } finally {
            payload.wipe()
        }
    }

    /** Sealed with [derived], the key that opened the file (a save): looked up nowhere, derived never. */
    fun write(
        root: XElement,
        binaries: List<Secret>,
        binaryFlags: List<Byte>,
        template: KdbxTemplate,
        derived: DerivedKey,
        random: SecureRandom = SecureRandom(),
    ): ByteArray {
        val payload = payload(root, binaries, binaryFlags, template, random)
        try {
            return seal(payload, derived)
        } finally {
            payload.wipe()
        }
    }

    /**
     * The first half of a write: the payload made from the tree (the inner header with the attachments, the XML), with
     * the new master seed, IV and inner stream key drawn for it. Nothing of [root] or [binaries] is needed after it, so
     * the caller may let them go before the file is assembled ([seal]): a large database's tree is larger than its file
     * (keething-spec app.large-database AC2). [PlainPayload.wipe] when done.
     */
    fun payload(
        root: XElement,
        binaries: List<Secret>,
        binaryFlags: List<Byte>,
        template: KdbxTemplate,
        random: SecureRandom = SecureRandom(),
    ): PlainPayload {
        if (template.cipher == KdbxFormat.Cipher.TWOFISH) throw KdbxException.UnsupportedCipher("Twofish")
        require(binaryFlags.size == binaries.size) { "a flag for every attachment" }
        WrittenDocuments.observer?.let { observe -> observe(XmlTree.withoutSecrets(root), KdbxSchema.violations(root)) }

        val masterSeed = ByteArray(32).also(random::nextBytes)
        val iv = ByteArray(if (template.cipher == KdbxFormat.Cipher.CHACHA20) 12 else 16).also(random::nextBytes)
        val streamKey = ByteArray(64).also(random::nextBytes)
        val plain = ChunkBuffer()
        try {
            writePayload(plain, root, binaries, binaryFlags, streamKey, template.compressed)
            return PlainPayload(plain, template, masterSeed, iv, minor = maxOf(template.minor, requiredMinor(root)))
        } catch (failure: Throwable) {
            plain.wipe()
            throw failure
        } finally {
            streamKey.fill(0)
        }
    }

    /** The second half: [payload] sealed into the file with [derived], the key that opened the file (a save). */
    fun seal(payload: PlainPayload, derived: DerivedKey): ByteArray {
        val transformed = derived.forKdf(payload.template.kdf)
        try {
            return seal(payload, transformed)
        } finally {
            transformed.fill(0)
        }
    }

    /** [payload] sealed with [key], derived now for the template's KDF parameters. */
    fun seal(payload: PlainPayload, key: DatabaseKey): ByteArray {
        val (transformed, _) = key.transformed(payload.template.kdf)
        try {
            return seal(payload, transformed)
        } finally {
            transformed.fill(0)
        }
    }

    /** [transformed] stays the caller's to wipe. */
    private fun seal(payload: PlainPayload, transformed: ByteArray): ByteArray {
        val template = payload.template
        val header = header(template, payload.masterSeed, payload.iv, payload.minor)
        val cipherKey = sha256(payload.masterSeed, transformed)
        val hmacBase = sha512(payload.masterSeed, transformed, byteArrayOf(1))
        try {
            return assemble(header, payload.plain, template.cipher, cipherKey, payload.iv, hmacBase)
        } finally {
            cipherKey.fill(0)
            hmacBase.fill(0)
        }
    }

    /**
     * KDBX 4.1 when the document uses what only 4.1 has (keepass.info/help/kb/kdbx_4.1.html): a `PreviousParentGroup`
     * (KeePassXC raises the version for it; KeePass's schema lists it as 4.1), an entry's `QualityCheck`, a group's
     * `Tags`, a custom icon's `Name` or `LastModificationTime`, a `CustomData` item's `LastModificationTime`. An empty
     * one, or a `PreviousParentGroup` of the null UUID, counts as none. Of these, the app itself writes only
     * `PreviousParentGroup` (an entry in the recycle bin); the version read is never lowered ([write]).
     */
    fun requiredMinor(root: XElement): Int {
        fun filled(element: XElement?): Boolean {
            val text = element?.text?.trim() ?: return false
            return text.isNotEmpty() && text != EntryLifecycle.NULL_UUID
        }
        fun uses41(element: XElement): Boolean {
            val uses = when (element.name) {
                "Entry" -> filled(element.first("PreviousParentGroup")) || filled(element.first("QualityCheck"))
                "Group" -> filled(element.first("PreviousParentGroup")) || filled(element.first("Tags"))
                "Icon" -> filled(element.first("Name")) || filled(element.first("LastModificationTime"))
                "Item" -> filled(element.first("LastModificationTime"))
                else -> false
            }
            return uses || element.elements.any(::uses41)
        }
        return if (uses41(root)) 1 else 0
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

    /**
     * The inner header, then the XML with the protected values sealed by the new inner stream; gzipped when asked. An
     * attachment that does not compress (an image, a PDF, an archive: its first 64 KiB tried) goes into the gzip stream
     * as stored blocks: deflating it would cost most of a large database's save and gain nothing (keething-spec
     * app.large-database AC1). It is still one gzip stream any KeePass app reads.
     */
    private fun writePayload(
        payload: ChunkBuffer,
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
                    if (gzip != null && !compresses(bytes)) gzip.stored { sink.write(bytes) } else sink.write(bytes)
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

    /**
     * The file in one array of its exact size: the header, its SHA-256 and HMAC, then the payload encrypted chunk by
     * chunk straight into the HMAC block stream (1 MiB blocks and an empty last one). No other copy of the file is made;
     * ChaCha20 encrypts the payload's chunks in place.
     */
    private fun assemble(
        header: ByteArray,
        payload: ChunkBuffer,
        cipher: KdbxFormat.Cipher,
        key: ByteArray,
        iv: ByteArray,
        hmacBase: ByteArray,
    ): ByteArray {
        val length = when (cipher) {
            // PKCS#7 padding: 1 to 16 bytes, a whole block when the payload is a multiple of 16.
            KdbxFormat.Cipher.AES256 -> (payload.size / 16 + 1) * 16
            KdbxFormat.Cipher.CHACHA20 -> payload.size
            KdbxFormat.Cipher.TWOFISH -> throw KdbxException.UnsupportedCipher("Twofish")
        }
        // The blocks with bytes, then the empty one.
        val blocks = (length + BLOCK_SIZE - 1) / BLOCK_SIZE + 1
        val size = header.size + 64L + blocks * 36L + length
        if (size > Int.MAX_VALUE - 8) throw OutOfMemoryError("a file of $size bytes")
        val file = ByteArray(size.toInt())
        header.copyInto(file)
        MessageDigest.getInstance("SHA-256").digest(header).copyInto(file, header.size)
        hmac(blockKey(-1L, hmacBase)) { update(header) }.copyInto(file, header.size + 32)
        val out = BlockStream(file, header.size + 64, hmacBase)
        when (cipher) {
            KdbxFormat.Cipher.CHACHA20 -> {
                val stream = ChaCha20(key, iv)
                try {
                    payload.forEachChunk { chunk, count ->
                        stream.apply(chunk, 0, count)
                        out.write(chunk, 0, count)
                    }
                } finally {
                    stream.wipe()
                }
            }
            else -> {
                val aes = Cipher.getInstance("AES/CBC/PKCS5Padding")
                    .apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }
                val encrypted = ByteArray(ChunkBuffer.CHUNK_SIZE + 16)
                payload.forEachChunk { chunk, count -> out.write(encrypted, 0, aes.update(chunk, 0, count, encrypted)) }
                out.write(encrypted, 0, aes.doFinal(encrypted, 0))
            }
        }
        out.finish()
        check(out.position == file.size)
        return file
    }

    /**
     * The HMAC block stream written into [file] from [start]: each block's bytes go in place, and its HMAC and size before
     * them once it is full (or [finish]ed), keyed by its index.
     */
    private class BlockStream(private val file: ByteArray, start: Int, private val hmacBase: ByteArray) {
        /** Where the next block (its HMAC and size, then its bytes) begins. */
        var position = start
            private set
        private var index = 0L
        private var filled = 0

        fun write(bytes: ByteArray, offset: Int, count: Int) {
            var from = offset
            val end = offset + count
            while (from < end) {
                val n = minOf(BLOCK_SIZE - filled, end - from)
                bytes.copyInto(file, position + 36 + filled, from, from + n)
                filled += n
                from += n
                if (filled == BLOCK_SIZE) seal()
            }
        }

        /** The last block, when it has bytes, then the empty one that ends the stream. */
        fun finish() {
            if (filled > 0) seal()
            seal()
        }

        private fun seal() {
            val key = blockKey(index, hmacBase)
            hmac(key) {
                update(Bytes.le64(index))
                update(Bytes.le32(filled))
                // The empty last block ends the file: its bytes start past the end.
                if (filled > 0) update(file, position + 36, filled)
            }.copyInto(file, position)
            key.fill(0)
            Bytes.putIntLe(file, position + 32, filled)
            position += 36 + filled
            index++
            filled = 0
        }
    }

    private fun blockKey(index: Long, base: ByteArray): ByteArray = sha512(Bytes.le64(index), base)

    private fun le16(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte())

    private fun sha256(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").apply { parts.forEach(::update) }.digest()

    private fun sha512(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-512").apply { parts.forEach(::update) }.digest()

    private inline fun hmac(key: ByteArray, feed: Mac.() -> Unit): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")); feed() }.doFinal()

    /** Whether [bytes] compress by a tenth at least, told from their first 64 KiB; small ones are taken to. */
    private fun compresses(bytes: ByteArray): Boolean {
        val sample = minOf(bytes.size, 64 * 1024)
        if (sample < 4096) return true
        val deflater = Deflater(Deflater.BEST_SPEED, true)
        val out = ByteArray(sample + 1024)
        try {
            deflater.setInput(bytes, 0, sample)
            deflater.finish()
            var size = 0
            while (!deflater.finished() && size < out.size) size += deflater.deflate(out, size, out.size - size)
            return size < sample * 9 / 10
        } finally {
            deflater.end()
            out.fill(0)
        }
    }

    /** Gzip whose own buffer, compressed plain text, is wiped at the end. */
    private class WipingGzip(out: OutputStream) : GZIPOutputStream(out, 64 * 1024) {
        /** [write] stored without compressing, then back to the default level. */
        fun stored(write: () -> Unit) {
            level(Deflater.NO_COMPRESSION)
            try {
                write()
            } finally {
                level(Deflater.DEFAULT_COMPRESSION)
            }
        }

        /**
         * [level] applied before any more input is given: zlib compresses the input it is holding with the level it had,
         * which would be the whole attachment.
         */
        private fun level(level: Int) {
            def.setLevel(level)
            def.setInput(ByteArray(0))
            while (true) {
                val n = def.deflate(buf, 0, buf.size)
                if (n == 0) break
                out.write(buf, 0, n)
            }
        }

        fun end() {
            buf.fill(0)
            def.end()
        }
    }
}

/** A payload made ([KdbxWriter.payload]) and not yet sealed: the plain bytes, and what the file's header will say. */
internal class PlainPayload(
    val plain: ChunkBuffer,
    val template: KdbxTemplate,
    val masterSeed: ByteArray,
    val iv: ByteArray,
    /** The version's minor number, raised to 4.1 when the document needs it ([KdbxWriter.requiredMinor]). */
    val minor: Int,
) {
    fun wipe() {
        plain.wipe()
    }
}

/**
 * The plain payload as it is written, in chunks of 1 MiB: it grows without copying what it holds, so a large file is
 * never held twice while it is made. [wipe] overwrites every chunk.
 */
internal class ChunkBuffer : OutputStream() {
    private val chunks = ArrayList<ByteArray>()
    private var used = CHUNK_SIZE

    var size = 0L
        private set

    override fun write(b: Int) {
        if (used == CHUNK_SIZE) next()
        chunks.last()[used++] = b.toByte()
        size++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        var from = off
        val end = off + len
        while (from < end) {
            if (used == CHUNK_SIZE) next()
            val n = minOf(CHUNK_SIZE - used, end - from)
            b.copyInto(chunks.last(), used, from, from + n)
            used += n
            from += n
        }
        size += len
    }

    /** Each chunk in order with the number of bytes it holds; [block] may change them in place. */
    fun forEachChunk(block: (chunk: ByteArray, count: Int) -> Unit) {
        chunks.forEachIndexed { index, chunk -> block(chunk, if (index == chunks.lastIndex) used else CHUNK_SIZE) }
    }

    fun wipe() {
        chunks.forEach { it.fill(0) }
        chunks.clear()
        used = CHUNK_SIZE
        size = 0
    }

    private fun next() {
        if (size + CHUNK_SIZE > Int.MAX_VALUE) throw OutOfMemoryError("a payload of more than 2 GiB")
        chunks += ByteArray(CHUNK_SIZE)
        used = 0
    }

    companion object {
        const val CHUNK_SIZE = 1 shl 20
    }
}
