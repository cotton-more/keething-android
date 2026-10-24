package dev.keepassbar.sharing.message

import java.security.MessageDigest
import java.util.Base64

/**
 * The text a sharing message travels as (SPEC §10): `keething:`, the message in base64url without padding, and
 * a `.` that ends it. The same text is what a QR code holds and what is sent or pasted as text; the end mark lets
 * a code broken over lines (an e-mail's wrapping) be read whole, words around it left out.
 *
 * A message too large for one QR code (an answer or an envelope of many entries) is split into parts: version 1, kind 3, the first 8 bytes of the whole
 * message's SHA-256, the part's index and the number of parts (a byte each), then its piece of the message.
 */
public object ShareCode {
    public const val PREFIX: String = "keething:"
    public const val END: Char = '.'

    /** A part's bytes before its piece. */
    private const val PART_HEADER = 2 + 8 + 2

    /** A message's largest size in a single QR code, as bytes before base64: version 23 at medium error correction. */
    public const val QR_BYTES: Int = 600

    /** More parts than this are too many to scan one after another. */
    public const val MAX_PARTS: Int = 16

    /** Text longer than this is not read: no real message is near it. */
    public const val MAX_TEXT: Int = 1 shl 20

    public fun text(message: ByteArray): String = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(message) + END

    /** What a message is; a [MalformedShareException] when it is none of them. */
    public sealed interface Read {
        public class Request(public val request: ShareRequest) : Read

        public class Answer(public val answer: ShareAnswer) : Read

        public class Part(public val part: CodePart) : Read

        public class Envelope(public val envelope: ShareEnvelope) : Read
    }

    public fun read(message: ByteArray): Read {
        if (message.size < 2) throw MalformedShareException("cut short")
        if (message[0].toInt() != Format.VERSION) throw MalformedShareException("a version this app does not know")
        return try {
            when (message[1].toInt()) {
                Format.KIND_REQUEST -> Read.Request(ShareRequest.decode(message))
                Format.KIND_ANSWER -> Read.Answer(ShareAnswer.decode(message))
                Format.KIND_PART -> Read.Part(CodePart.decode(message))
                Format.KIND_ENVELOPE -> Read.Envelope(ShareEnvelope.decode(message))
                else -> throw MalformedShareException("another kind of code")
            }
        } catch (failure: RuntimeException) {
            // Whatever a crafted code makes the platform throw, it is a code that cannot be read.
            throw MalformedShareException(failure.javaClass.simpleName)
        }
    }

    /** The messages in [text] and how many codes in it could not be read (cut short, changed). */
    public class Found(public val messages: List<ByteArray>, public val broken: Int)

    public fun find(text: String): Found {
        if (text.length > MAX_TEXT) return Found(emptyList(), 1)
        val messages = mutableListOf<ByteArray>()
        var broken = 0
        var from = 0
        while (true) {
            val start = text.indexOf(PREFIX, from, ignoreCase = true)
            if (start < 0) break
            val body = StringBuilder()
            var at = start + PREFIX.length
            var ended = false
            while (at < text.length) {
                val c = text[at]
                when {
                    c == END -> { ended = true; break }
                    c.isWhitespace() -> {}
                    c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' -> body.append(c)
                    else -> break
                }
                at++
            }
            from = at
            val decoded = if (ended) runCatching { Base64.getUrlDecoder().decode(body.toString()) }.getOrNull() else null
            if (decoded != null && decoded.isNotEmpty()) messages += decoded else broken++
        }
        return Found(messages, broken)
    }

    /** The texts of the QR codes that carry [message]: itself when it fits one, else up to [MAX_PARTS] parts. */
    public fun qrTexts(message: ByteArray, maxBytes: Int = QR_BYTES): List<String> {
        if (message.size <= maxBytes) return listOf(text(message))
        val piece = maxBytes - PART_HEADER
        val count = (message.size + piece - 1) / piece
        if (count > MAX_PARTS) throw IllegalArgumentException("too large for QR codes: $count parts")
        val id = messageId(message)
        return (0 until count).map { index ->
            val writer = WireWriter(maxBytes)
            writer.byte(Format.VERSION)
            writer.byte(Format.KIND_PART)
            writer.bytes(id)
            writer.byte(index)
            writer.byte(count)
            writer.bytes(message.copyOfRange(index * piece, minOf(message.size, (index + 1) * piece)))
            text(writer.toByteArray())
        }
    }

    internal fun messageId(message: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(message).copyOf(8)
}

/** One of the QR codes a large message is split into ([ShareCode.qrTexts]). */
public class CodePart private constructor(
    internal val messageId: ByteArray,
    /** From 0. */
    public val index: Int,
    public val count: Int,
    internal val piece: ByteArray,
) {
    internal val key: String get() = messageId.joinToString("") { "%02x".format(it) }

    public companion object {
        internal fun decode(bytes: ByteArray): CodePart {
            val reader = WireReader(bytes)
            Format.header(reader, Format.KIND_PART)
            val id = reader.bytes(8)
            val index = reader.byte()
            val count = reader.byte()
            if (count !in 2..ShareCode.MAX_PARTS || index >= count || reader.remaining == 0) {
                throw MalformedShareException("a part out of range")
            }
            return CodePart(id, index, count, reader.bytes(reader.remaining))
        }
    }
}

/**
 * The parts read so far, in any order (SPEC §10: "Part 1 of 2 read"). Parts of another message start over; the
 * whole is checked against the id its parts carry, so pieces of two messages never make one.
 */
public class PartCollector {
    private var key: String? = null
    private val pieces = sortedMapOf<Int, ByteArray>()
    private var count = 0

    public sealed interface Progress {
        /** [read] of [count] parts so far. */
        public data class Waiting(val read: Int, val count: Int) : Progress

        public class Complete(public val message: ByteArray) : Progress
    }

    public fun add(part: CodePart): Progress {
        if (part.key != key || part.count != count) {
            key = part.key
            count = part.count
            pieces.clear()
        }
        pieces[part.index] = part.piece
        if (pieces.size < count) return Progress.Waiting(pieces.size, count)
        val whole = pieces.values.fold(ByteArray(0)) { all, piece -> all + piece }
        clear()
        if (!ShareCode.messageId(whole).contentEquals(part.messageId)) throw MalformedShareException("parts that do not belong together")
        return Progress.Complete(whole)
    }

    public fun clear() {
        key = null
        count = 0
        pieces.clear()
    }
}
