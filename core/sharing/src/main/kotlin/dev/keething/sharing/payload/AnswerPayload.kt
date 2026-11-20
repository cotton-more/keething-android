package dev.keething.sharing.payload

import dev.keething.domain.secret.Secret
import dev.keething.domain.totp.TotpConfig
import dev.keething.domain.vault.CustomField
import dev.keething.domain.vault.EntryChanges
import dev.keething.domain.vault.EntryType
import dev.keething.domain.vault.FieldValue
import dev.keething.sharing.message.MalformedShareException
import dev.keething.sharing.message.ShareRequest
import dev.keething.sharing.message.WireReader
import dev.keething.sharing.message.WireWriter
import java.time.Instant

/**
 * One entry as an answer carries it (SPEC §10): what the sender chose to send of it. No attachments, history,
 * icon or id: the receiving phone makes a new entry of it.
 */
public class SharedEntry(
    public val title: String,
    public val username: String = "",
    public val password: Secret = Secret.empty(),
    public val url: String = "",
    public val notes: String = "",
    /** In the entry's order; protected ones stay protected. */
    public val customFields: List<CustomField> = emptyList(),
    /** The types of [customFields] that have one (SPEC §6.3). */
    public val fieldTypes: Map<String, String> = emptyMap(),
    public val tags: List<String> = emptyList(),
    /** KeePass's expiry; null when the entry does not expire. */
    public val expires: Instant? = null,
    public val totp: TotpConfig? = null,
    /** Notes protected in the sender's file (SPEC §6.4); said only when the Notes go. */
    public val notesProtected: Boolean = false,
    /** The entry's kind as the sender's file stores it (`Keething.EntryType`), a value this version does not know too. */
    public val entryType: String? = null,
) {
    /** A note as its card will call it once saved ([dev.keething.domain.vault.Entry.isNote]'s rule). */
    public val isNote: Boolean
        get() = EntryType.isNote(entryType, notes, username, password, url, customFields.isEmpty() && totp == null)

    /** Protected Notes that are there to protect: an empty value sends nothing protected. */
    internal val sendsProtectedNotes: Boolean get() = notesProtected && notes.isNotEmpty()

    public fun wipe() {
        password.wipe()
        customFields.forEach { (it.value as? FieldValue.Protected)?.secret?.wipe() }
        totp?.secret?.wipe()
    }
}

/**
 * What an answer holds, sealed: the sender's name as they gave it, and the entries.
 *
 * Its bytes: version 1, the sender's name, the number of entries, then each entry: title, username, password,
 * URL, notes, the tags, the expiry (a byte 1 and the seconds, or 0), the custom fields (name, a byte 1 when
 * protected, value), the field types (name, type), and the one-time code (a byte 1, the key, digits, period
 * and algorithm 0 SHA-1, 1 SHA-256, 2 SHA-512; or 0). Text is UTF-8 after its length. Last, zeros after their
 * length up to a multiple of 128 bytes: the answer's length tells little about how long a password is.
 *
 * Version 2 (keething-spec sharing.content AC9) has two more items after an entry's notes: a byte 1 when they are
 * protected (else 0), and the kind (a byte 1 and the stored value, or 0). It is written only when an entry needs it,
 * so a phone that reads only version 1 still gets everything else; both are read.
 */
public class AnswerPayload(public val from: String, public val entries: List<SharedEntry>) {
    public fun wipe() {
        entries.forEach(SharedEntry::wipe)
    }

    /**
     * The bytes as a secret: they hold passwords. Read back at once by the receiver's rules, so what one side makes the
     * other reads: an [IllegalArgumentException] says what it would refuse (too many entries, a field named as KeePass's
     * own, a control character).
     */
    internal fun encode(): Secret {
        val bytes = write()
        try {
            bytes.useBytes { decode(it) }.wipe()
        } catch (refused: MalformedShareException) {
            bytes.wipe()
            throw IllegalArgumentException("the answer would not be read: ${refused.message}") // English detail
        }
        return bytes
    }

    private fun write(): Secret {
        val version = if (entries.any { it.sendsProtectedNotes || it.entryType != null }) VERSION_NOTES else VERSION
        val w = WireWriter(1024)
        try {
            w.byte(version)
            w.text(from)
            w.count(entries.size)
            for (e in entries) {
                w.text(e.title)
                w.text(e.username)
                w.secret(e.password)
                w.text(e.url)
                w.text(e.notes)
                if (version == VERSION_NOTES) {
                    w.byte(if (e.sendsProtectedNotes) 1 else 0)
                    if (e.entryType != null) {
                        w.byte(1)
                        w.text(e.entryType)
                    } else {
                        w.byte(0)
                    }
                }
                w.count(e.tags.size)
                e.tags.forEach(w::text)
                if (e.expires != null) {
                    w.byte(1)
                    w.long(e.expires.epochSecond)
                } else {
                    w.byte(0)
                }
                w.count(e.customFields.size)
                for (field in e.customFields) {
                    w.text(field.name)
                    when (val value = field.value) {
                        is FieldValue.Protected -> { w.byte(1); w.secret(value.secret) }
                        is FieldValue.Plain -> { w.byte(0); w.text(value.text) }
                    }
                }
                w.count(e.fieldTypes.size)
                e.fieldTypes.forEach { (name, type) -> w.text(name); w.text(type) }
                val totp = e.totp
                if (totp != null) {
                    w.byte(1)
                    w.secret(totp.secret)
                    w.byte(totp.digits)
                    w.count(totp.period)
                    w.byte(totp.algorithm.ordinal)
                } else {
                    w.byte(0)
                }
            }
            w.field(ByteArray((PADDING - (w.size + 1) % PADDING) % PADDING))
            return w.toSecret()
        } finally {
            w.wipe()
        }
    }

    public companion object {
        private const val VERSION = 1
        private const val VERSION_NOTES = 2
        public const val MAX_ENTRIES: Int = 50
        private const val MAX_FIELDS = 500
        private const val MAX_TEXT = 1 shl 20
        private const val PADDING = 128

        /** Reads [bytes]; whatever it took out of them is the caller's to wipe, [bytes] stay the caller's too. */
        internal fun decode(bytes: ByteArray): AnswerPayload {
            val taken = mutableListOf<SharedEntry>()
            try {
                // One form only: padded to a multiple of 128, as the length's other forms are refused too.
                if (bytes.size % PADDING != 0) throw MalformedShareException("a length not padded") // English detail
                val r = WireReader(bytes)
                val version = r.byte()
                if (version != VERSION && version != VERSION_NOTES) throw MalformedShareException("a version this app does not know") // English detail
                val from = r.text(MAX_TEXT)
                if (!ShareRequest.isLine(from, ShareRequest.MAX_NAME_LENGTH)) throw MalformedShareException("a sender's name") // English detail
                repeat(r.count(MAX_ENTRIES)) { taken += entry(r, version) }
                if (r.field(PADDING).any { it != 0.toByte() }) throw MalformedShareException("padding")
                r.end()
                if (taken.isEmpty()) throw MalformedShareException("no entries") // English detail
                return AnswerPayload(from, taken.toList())
            } catch (failure: MalformedShareException) {
                taken.forEach(SharedEntry::wipe)
                throw failure
            } catch (failure: RuntimeException) {
                taken.forEach(SharedEntry::wipe)
                throw MalformedShareException(failure.javaClass.simpleName)
            }
        }

        private fun entry(r: WireReader, version: Int): SharedEntry {
            val title = r.xmlText()
            val username = r.xmlText()
            val password = r.xmlSecret()
            val secrets = mutableListOf(password)
            try {
                val url = r.xmlText()
                val notes = r.xmlText()
                var notesProtected = false
                var entryType: String? = null
                if (version == VERSION_NOTES) {
                    notesProtected = when (r.byte()) {
                        0 -> false
                        1 -> true
                        else -> throw MalformedShareException("Notes' protection") // English detail
                    }
                    entryType = when (r.byte()) {
                        0 -> null
                        1 -> r.xmlText()
                        else -> throw MalformedShareException("a kind")
                    }
                }
                val tags = List(r.count(MAX_FIELDS)) { r.xmlText() }
                val expires = when (r.byte()) {
                    0 -> null
                    1 -> r.instant()
                    else -> throw MalformedShareException("an expiry") // English detail
                }
                val fields = List(r.count(MAX_FIELDS)) {
                    val name = r.xmlText()
                    val value = when (r.byte()) {
                        0 -> FieldValue.Plain(r.xmlText())
                        1 -> FieldValue.Protected(r.xmlSecret().also(secrets::add))
                        else -> throw MalformedShareException("a field")
                    }
                    CustomField(name, value)
                }
                val names = fields.map { it.name }
                if (names.any { it.isEmpty() || it in EntryChanges.RESERVED_FIELD_NAMES } || names.toSet().size != names.size) {
                    throw MalformedShareException("a field's name")
                }
                val types = LinkedHashMap<String, String>()
                repeat(r.count(MAX_FIELDS)) {
                    val name = r.xmlText()
                    if (name !in names || name in types) throw MalformedShareException("a field's type")
                    types[name] = r.xmlText()
                }
                val totp = when (r.byte()) {
                    0 -> null
                    1 -> {
                        val key = r.secret(1024).also(secrets::add)
                        val digits = r.byte()
                        val period = r.count(24 * 3600)
                        val algorithm = TotpConfig.Algorithm.entries.getOrNull(r.byte())
                        if (key.isEmpty() || digits !in 1..10 || period < 1 || algorithm == null) {
                            throw MalformedShareException("a one-time code") // English detail
                        }
                        TotpConfig(key, digits, period, algorithm)
                    }
                    else -> throw MalformedShareException("a one-time code") // English detail
                }
                return SharedEntry(title, username, password, url, notes, fields, types, tags, expires, totp, notesProtected, entryType)
            } catch (failure: Exception) {
                secrets.forEach(Secret::wipe)
                throw failure
            }
        }

        /**
         * Text a KeePass file can hold: XML 1.0 has no control characters but tab, line feed and carriage return.
         * Refused here, not by the save's check after the user chose to save.
         */
        private fun WireReader.xmlText(): String = text(MAX_TEXT).also { text ->
            if (text.any { it < ' ' && it !in "\t\n\r" || it == '\uFFFE' || it == '\uFFFF' }) throw MalformedShareException("a control character") // English detail
        }

        /** A secret as [xmlText] is text, checked on its bytes: nothing of it is decoded into a String. */
        private fun WireReader.xmlSecret(): Secret = secret(MAX_TEXT).also { secret ->
            if (!secret.useBytes(::isXmlUtf8)) {
                secret.wipe()
                throw MalformedShareException("a secret that is not text") // English detail
            }
        }

        /** Strict UTF-8 (no overlong form, surrogate or code point beyond U+10FFFF) of characters XML can hold. */
        private fun isXmlUtf8(bytes: ByteArray): Boolean {
            var i = 0
            while (i < bytes.size) {
                val lead = bytes[i].toInt() and 0xFF
                val (length, least) = when {
                    lead < 0x80 -> 1 to 0
                    lead in 0xC2..0xDF -> 2 to 0x80
                    lead in 0xE0..0xEF -> 3 to 0x800
                    lead in 0xF0..0xF4 -> 4 to 0x10000
                    else -> return false
                }
                if (i + length > bytes.size) return false
                var point = if (length == 1) lead else lead and (0xFF shr (length + 1))
                for (k in 1 until length) {
                    val next = bytes[i + k].toInt() and 0xFF
                    if (next and 0xC0 != 0x80) return false
                    point = (point shl 6) or (next and 0x3F)
                }
                val xml = point >= 0x20 || point == 0x09 || point == 0x0A || point == 0x0D
                if (point < least || point > 0x10FFFF || point in 0xD800..0xDFFF || point == 0xFFFE || point == 0xFFFF || !xml) return false
                i += length
            }
            return true
        }
    }
}
