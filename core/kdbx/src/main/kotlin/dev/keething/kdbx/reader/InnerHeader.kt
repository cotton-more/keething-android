package dev.keething.kdbx.reader

import dev.keething.domain.secret.Secret
import dev.keething.kdbx.crypto.Bytes
import dev.keething.kdbx.crypto.KeyStream
import java.io.IOException
import java.io.InputStream
import java.util.Arrays

/**
 * The header at the start of the decrypted payload: the key of the stream that hides protected values, and
 * the attachments (Mac `InnerHeader`).
 */
internal class InnerHeader(
    private val streamId: Int,
    private val streamKey: ByteArray,
    /** Wipeable: an attachment may be a private key. */
    val binaries: List<Secret>,
    /** Each attachment's flag byte (0x01: protected in memory), written back as it was. */
    val binaryFlags: List<Byte>,
    /** Read with attachments [expected][read]: whether these were others (or another number of them). */
    val binariesDiffer: Boolean = false,
) {
    /** The stream its id names (ChaCha20, KDBX 4's only stream in practice), keyed with its key. */
    fun stream(): KeyStream = InnerStream.of(streamId, streamKey)

    fun wipeKey() {
        streamKey.fill(0)
    }

    companion object {
        /**
         * Reads the inner header from the start of the payload; [input] is left where the XML starts. With [expected]
         * (a save's read-back), each attachment is compared with the one at its place as it is read and not kept:
         * [binaries] are then [expected] themselves, and [binariesDiffer] says whether the file holds the same.
         */
        fun read(input: InputStream, expected: List<Secret>? = null): InnerHeader {
            var streamId: Int? = null
            var key: ByteArray? = null
            val binaries = mutableListOf<Secret>()
            val flags = mutableListOf<Byte>()
            var differ = false
            try {
                while (true) {
                    val id = input.read()
                    // A size beyond what an array can hold is a damaged header too.
                    val size = int32(input)?.takeIf { it >= 0 }
                    if (id < 0 || size == null) throw KdbxException.Corrupt("inner header")
                    when (id) {
                        0 -> {
                            skip(input, size)
                            if (streamId == null || key == null) throw KdbxException.Corrupt("inner header")
                            if (expected != null && flags.size != expected.size) differ = true
                            return InnerHeader(streamId, key, expected ?: binaries, flags, differ)
                        }
                        1 -> {
                            if (size != 4) throw KdbxException.Corrupt("inner header")
                            streamId = int32(input) ?: throw KdbxException.Corrupt("inner header")
                        }
                        2 -> key = bytes(input, size)
                        // The first byte holds flags (protected in memory); the rest is the attachment.
                        3 -> {
                            if (size == 0) throw KdbxException.Corrupt("inner header binary")
                            val flag = input.read()
                            if (flag < 0) throw KdbxException.Corrupt("inner header")
                            val held = expected?.getOrNull(flags.size)
                            flags += flag.toByte()
                            when {
                                expected == null -> binaries += Secret.adopt(bytes(input, size - 1))
                                held == null -> {
                                    differ = true
                                    skip(input, size - 1)
                                }
                                !same(input, size - 1, held) -> differ = true
                            }
                        }
                        else -> skip(input, size)
                    }
                }
            } catch (failure: PayloadFailure) {
                binaries.forEach(Secret::wipe)
                key?.fill(0)
                throw failure.failure
            } catch (failure: KdbxException) {
                binaries.forEach(Secret::wipe)
                key?.fill(0)
                throw failure
            }
        }

        /** A little-endian 32-bit number (negative above 2^31 - 1); null at the end of the stream. */
        private fun int32(input: InputStream): Int? {
            val bytes = ByteArray(4)
            return if (readFully(input, bytes, 0, 4) < 4) null else Bytes.intLe(bytes, 0)
        }

        private fun bytes(input: InputStream, size: Int): ByteArray {
            val bytes = ByteArray(size)
            if (readFully(input, bytes, 0, size) < size) {
                bytes.fill(0)
                throw KdbxException.Corrupt("inner header")
            }
            return bytes
        }

        private fun skip(input: InputStream, size: Int) {
            val scratch = ByteArray(minOf(size, CHUNK))
            var left = size
            while (left > 0) {
                val n = readFully(input, scratch, 0, minOf(left, scratch.size))
                if (n <= 0) throw KdbxException.Corrupt("inner header")
                left -= n
            }
            scratch.fill(0)
        }

        /** Whether the next [size] bytes of [input] are [held]'s; all [size] are read either way. */
        private fun same(input: InputStream, size: Int, held: Secret): Boolean = held.useBytes { bytes ->
            val scratch = ByteArray(minOf(size, CHUNK))
            var same = bytes.size == size
            var at = 0
            while (at < size) {
                val n = readFully(input, scratch, 0, minOf(size - at, scratch.size))
                if (n <= 0) throw KdbxException.Corrupt("inner header")
                if (same) same = Arrays.equals(scratch, 0, n, bytes, at, at + n)
                at += n
            }
            scratch.fill(0)
            same
        }

        private fun readFully(input: InputStream, into: ByteArray, offset: Int, count: Int): Int = try {
            input.readNBytes(into, offset, count)
        } catch (failure: PayloadFailure) {
            throw failure
        } catch (_: IOException) {
            throw KdbxException.Corrupt("inner header")
        }

        private const val CHUNK = 64 * 1024
    }
}
