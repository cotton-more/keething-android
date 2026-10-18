package dev.keepassbar.domain.secret

import java.security.MessageDigest

/**
 * A secret's bytes while they are needed (SPEC §4): the master password, an entry's password and protected
 * fields, a one-time code's key. Text secrets are UTF-8.
 *
 * A class with one buffer on purpose: every holder shares it, so [wipe] reaches it wherever it is held (the
 * lock wipes the whole vault this way). A `String` cannot be wiped; [reveal] makes one only for the moment
 * a value is shown, copied or filled. [toString] never shows the bytes, so a secret cannot end up in a log
 * by accident.
 */
public class Secret private constructor(private val bytes: ByteArray) {
    @Volatile
    private var wiped = false

    public val size: Int get() = bytes.size

    public fun isEmpty(): Boolean = bytes.isEmpty()

    /** True once overwritten; the bytes are then all zero. */
    public val isWiped: Boolean get() = wiped

    /** The text, for showing, copying or filling it. */
    public fun reveal(): String = bytes.decodeToString()

    /** Runs [block] on the bytes themselves; it must not keep or change them. */
    public fun <R> useBytes(block: (ByteArray) -> R): R = block(bytes)

    /** A copy with a buffer of its own. */
    public fun copy(): Secret = Secret(bytes.copyOf())

    /** Overwrites the bytes with zeros, for every holder at once. */
    public fun wipe() {
        bytes.fill(0)
        wiped = true
    }

    /** Same bytes, compared in constant time. */
    public fun hasSameBytes(other: Secret): Boolean = MessageDigest.isEqual(bytes, other.bytes)

    override fun equals(other: Any?): Boolean = other is Secret && hasSameBytes(other)

    // Constant: a hash would tell something about the bytes.
    override fun hashCode(): Int = 0

    override fun toString(): String = "Secret(${if (wiped) "wiped" else "•••"})"

    public companion object {
        /** The UTF-8 bytes of [text]. The `String` itself cannot be wiped: the caller drops it soon. */
        public fun of(text: String): Secret = Secret(text.encodeToByteArray())

        /** Takes [bytes] as the secret's buffer, without a copy: the caller must not use them afterwards. */
        public fun adopt(bytes: ByteArray): Secret = Secret(bytes)

        /** A copy of [bytes] from [from] (inclusive) to [to] (exclusive). */
        public fun copyOf(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): Secret =
            Secret(bytes.copyOfRange(from, to))

        public fun empty(): Secret = Secret(ByteArray(0))
    }
}
