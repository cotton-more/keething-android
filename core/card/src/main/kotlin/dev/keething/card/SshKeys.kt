package dev.keething.card

import dev.keething.domain.vault.Attachment
import java.security.MessageDigest
import java.util.Base64

/**
 * Where KeePassXC's SSH agent integration finds an entry's key (Mac SPEC §3.15, Mac `KeeAgentSettings`, read only
 * here): the `KeeAgent.settings` attachment names an attachment of the entry or a file on the computer. The phone runs
 * no agent; the SSH face only says where the key is and shows its public half.
 */
public sealed interface SshKeyLocation {
    public val name: String

    public data class InAttachment(override val name: String) : SshKeyLocation

    public data class InFile(val path: String) : SshKeyLocation {
        override val name: String get() = path
    }

    public companion object {
        /** KeePassXC's marker of an entry with an SSH key. */
        public const val SETTINGS_ATTACHMENT: String = "KeeAgent.settings" // not user text

        public fun hasSettings(attachments: List<Attachment>): Boolean = attachments.any { it.name == SETTINGS_ATTACHMENT }

        /**
         * The key's place from the settings (UTF-16 XML as KeePassXC writes it, UTF-8 read too); when they cannot be
         * read or name none, the attachment that looks like the key (the only one besides the settings and `.pub`
         * files, else the only one named `id_…`), as the Mac guesses it.
         */
        public fun of(settings: ByteArray?, attachments: List<Attachment>): SshKeyLocation? {
            settings?.let(::decode)?.takeIf { "<EntrySettings" in it }?.let { text -> // not user text
                val kind = value(text, "SelectedType") ?: "file" // not user text
                when (kind.lowercase()) {
                    "attachment" -> value(text, "AttachmentName")?.takeIf { it.isNotEmpty() }?.let { return InAttachment(it) } // not user text
                    "file" -> value(text, "FileName")?.takeIf { it.isNotEmpty() }?.let { return InFile(it) } // not user text
                }
            }
            val candidates = attachments.map { it.name }.filter { it != SETTINGS_ATTACHMENT && !it.lowercase().endsWith(".pub") }
            val named = candidates.filter { it.startsWith("id_") }
            return when {
                candidates.size == 1 -> InAttachment(candidates[0])
                named.size == 1 -> InAttachment(named[0])
                else -> null
            }
        }

        private fun decode(bytes: ByteArray): String? = runCatching {
            when {
                bytes.size >= 2 && (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() || bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) ->
                    String(bytes, Charsets.UTF_16)
                bytes.size >= 2 && bytes[1] == 0.toByte() -> String(bytes, Charsets.UTF_16LE)
                bytes.size >= 2 && bytes[0] == 0.toByte() -> String(bytes, Charsets.UTF_16BE)
                else -> String(bytes, Charsets.UTF_8)
            }
        }.getOrNull()

        private fun value(text: String, tag: String): String? {
            val open = text.indexOf("<$tag>").takeIf { it >= 0 } ?: return null
            val start = open + tag.length + 2
            val close = text.indexOf("</$tag>", start).takeIf { it >= 0 } ?: return null
            return text.substring(start, close).trim()
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
        }
    }
}

/** The public half of an SSH key (Mac `SSHPublicKey`): what `authorized_keys` holds. Not a secret. */
public class SshPublicKey private constructor(private val blob: ByteArray) {
    /** `ssh-ed25519`, `ssh-rsa`, `ecdsa-sha2-nistp256`, … */
    public val type: String = wireString(blob)?.decodeToString().orEmpty()

    /** `ED25519`, `RSA`, `ECDSA`, as `ssh-keygen -l` names them. */
    public val displayType: String
        get() = when {
            type == "ssh-ed25519" -> "ED25519"
            type == "ssh-rsa" -> "RSA"
            type == "ssh-dss" -> "DSA"
            type.startsWith("ecdsa-") -> "ECDSA"
            type.startsWith("sk-ssh-ed25519") -> "ED25519-SK"
            type.startsWith("sk-ecdsa-") -> "ECDSA-SK"
            else -> type
        }

    /** `SHA256:…` as `ssh-keygen -l` prints it (base64 without padding). */
    public val fingerprint: String
        get() = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))

    /** The `authorized_keys` line, without a comment. */
    public val line: String get() = "$type ${Base64.getEncoder().encodeToString(blob)}"

    override fun equals(other: Any?): Boolean = other is SshPublicKey && blob.contentEquals(other.blob)

    override fun hashCode(): Int = blob.contentHashCode()

    override fun toString(): String = "SshPublicKey($displayType $fingerprint)" // not user text

    /** What a private key's file gave. */
    public sealed interface Read {
        public data class Key(val key: SshPublicKey) : Read

        /** Not in the OpenSSH format (PKCS#8, PEM, PuTTY, or no key at all). */
        public data object OtherFormat : Read

        /** OpenSSH text that cannot be read: cut short or broken. */
        public data object Unreadable : Read
    }

    public companion object {
        private val HEADER = "-----BEGIN OPENSSH PRIVATE KEY-----".encodeToByteArray() // not user text
        private val MAGIC = "openssh-key-v1\u0000".encodeToByteArray()

        /** As far as the public key is decoded at most. */
        private const val LIMIT = 17_000

        /**
         * The public key of a private key in the OpenSSH format (Mac `OpenSSHPrivateKey.publicKey`). The format keeps it
         * in the clear in front of the private part, also under a passphrase, so the base64 text is decoded only up to
         * its end, into a buffer wiped afterwards. Without the OpenSSH header it is [Read.OtherFormat]; OpenSSH text
         * that cannot be read is [Read.Unreadable] (the Mac's `notOpenSSHFormat` and `malformed`).
         */
        public fun ofPrivateKey(key: ByteArray): Read {
            val start = indexOf(key, HEADER) ?: return Read.OtherFormat
            return publicKey(key, start)?.let(Read::Key) ?: Read.Unreadable
        }

        private fun publicKey(key: ByteArray, start: Int): SshPublicKey? {
            val decoder = PrefixBase64(key, start + HEADER.size)
            try {
                if (!decoder.decode(MAGIC.size) || !decoder.output.copyOf(MAGIC.size).contentEquals(MAGIC)) return null
                var offset = MAGIC.size
                // cipher, kdf, kdf options, then the number of keys and the first public key; each length is checked
                // against the room left, so a broken one never reaches past the buffer.
                repeat(3) { offset += 4 + (decoder.uint32(offset)?.takeIf { it <= LIMIT - offset - 8 } ?: return null).toInt() }
                offset += 4
                val length = decoder.uint32(offset)?.takeIf { it in 1 until 16_384 && it <= LIMIT - offset - 4 }?.toInt() ?: return null
                if (!decoder.decode(offset + 4 + length)) return null
                val blob = decoder.output.copyOfRange(offset + 4, offset + 4 + length)
                return SshPublicKey(blob).takeIf { it.type.isNotEmpty() }
            } finally {
                decoder.wipe()
            }
        }

        private fun wireString(data: ByteArray): ByteArray? {
            if (data.size < 4) return null
            val length = data.take(4).fold(0L) { acc, b -> acc shl 8 or (b.toLong() and 0xFF) }
            if (length <= 0 || length > data.size - 4) return null
            return data.copyOfRange(4, 4 + length.toInt())
        }

        private fun indexOf(bytes: ByteArray, needle: ByteArray): Int? {
            outer@ for (i in 0..bytes.size - needle.size) {
                for (j in needle.indices) if (bytes[i + j] != needle[j]) continue@outer
                return i
            }
            return null
        }
    }

    /** Decodes base64 from a buffer, skipping line breaks, only as far as asked: the private part is never decoded. */
    private class PrefixBase64(private val source: ByteArray, private var position: Int) {
        var output = ByteArray(LIMIT + 2) // room for the last group of four, which may pass the count asked
        var size = 0
        private val quad = IntArray(4)
        private var filled = 0

        fun decode(count: Int): Boolean {
            if (count > LIMIT) return false
            while (size < count) {
                if (position >= source.size) return false
                val c = source[position++].toInt().toChar()
                if (c == '-') return false
                val v = value(c) ?: continue
                quad[filled++] = v
                if (filled == 4) {
                    output[size++] = (quad[0] shl 2 or (quad[1] shr 4)).toByte()
                    if (quad[2] != 64) output[size++] = (quad[1] shl 4 or (quad[2] shr 2)).toByte()
                    if (quad[3] != 64) output[size++] = (quad[2] shl 6 or quad[3]).toByte()
                    filled = 0
                }
            }
            return true
        }

        /** Unsigned, so that a length of 2³¹ or more is not read as negative. */
        fun uint32(offset: Int): Long? {
            if (offset < 0 || !decode(offset + 4)) return null
            return (0 until 4).fold(0L) { acc, i -> acc shl 8 or (output[offset + i].toLong() and 0xFF) }
        }

        fun wipe() {
            output.fill(0)
            quad.fill(0)
        }

        private fun value(c: Char): Int? = when (c) {
            in 'A'..'Z' -> c - 'A'
            in 'a'..'z' -> c - 'a' + 26
            in '0'..'9' -> c - '0' + 52
            '+' -> 62
            '/' -> 63
            '=' -> 64
            else -> null
        }
    }
}
