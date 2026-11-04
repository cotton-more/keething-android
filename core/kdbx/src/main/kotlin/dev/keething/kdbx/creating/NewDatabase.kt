package dev.keething.kdbx.creating

import dev.keething.domain.vault.Entry
import dev.keething.kdbx.editing.EditException
import dev.keething.kdbx.editing.EntryLifecycle
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.kdbx.key.KdfLimits
import dev.keething.kdbx.reader.Header
import dev.keething.kdbx.reader.KdbxException
import dev.keething.kdbx.reader.KdbxFormat.Cipher
import dev.keething.kdbx.reader.KdbxFormat.Kdf
import dev.keething.kdbx.reader.KdbxTemplate
import dev.keething.kdbx.reader.VariantDictionary
import dev.keething.kdbx.schema.KdbxSchema
import dev.keething.kdbx.tree.XElement
import dev.keething.kdbx.writer.KdbxWriter
import dev.keething.kdbx.xml.KdbxTime
import java.security.SecureRandom
import java.time.Instant

/**
 * A new database file (keething-spec kdbx.new-file; the Mac's `KDBXTemplate.new` and `NewDatabase`): an ordinary
 * KeePass file every current KeePass app opens. KDBX 4.0, gzip, AES-256 or ChaCha20, Argon2d, Argon2id or AES-KDF
 * with a fresh 32-byte salt, the parameters as KeePass writes them; its XML as keepassxc-cli 2.7.12's `db-create`
 * writes it, as KDBX 4 (base64 times), with Generator "Keething" and none of KeePassXC's own custom data. One root
 * group named as the database, no other groups, no entries; the recycle bin is made by the first delete
 * ([EntryLifecycle]), as KeePassXC makes it.
 */
public object NewDatabase {
    public const val GENERATOR: String = "Keething"

    /**
     * The new file's bytes. [name] is its `Meta/DatabaseName` (null or blank: none; the root group then takes
     * [fileName]). Derives [key] for the new salt, which takes as long as [kdf] says (call it off the main thread);
     * the key keeps the derived key, so the first unlock derives nothing. Throws [KdbxException.UnsupportedCipher]
     * or [KdbxException.UnsupportedKdf] for what is not written, before anything is derived, and
     * [EditException.BreaksFormat] for a document KeePass's schema would not allow.
     */
    public fun file(
        name: String?,
        fileName: String,
        cipher: Cipher,
        kdf: Kdf,
        key: DatabaseKey,
        now: Instant,
        random: SecureRandom = SecureRandom(),
    ): ByteArray {
        val template = template(cipher, kdf, random)
        val root = document(name, fileName, now)
        KdbxSchema.violations(root).minOrNull()?.let { throw EditException.BreaksFormat(it.describe()) }
        return KdbxWriter.write(root, emptyList(), emptyList(), template, key, random)
    }

    /** KDF parameters every KeePass app runs (kdbx.new-file AC3): within KeePass 2.x's own limits ([KdfLimits]). */
    public fun check(kdf: Kdf) {
        KdfLimits.problem(kdf)?.let { throw KdbxException.UnsupportedKdf(it) }
    }

    /**
     * The root group's name: the database's name; without one, the file name without `.kdbx` (as KeePass 2.x names
     * it); never empty.
     */
    public fun rootGroupName(name: String?, fileName: String): String {
        trimmed(name)?.let { return it }
        val base = if (fileName.endsWith(".kdbx", ignoreCase = true)) fileName.dropLast(5) else fileName
        return trimmed(base) ?: "Root"
    }

    /** `Meta/DatabaseName` for the name given: without its outer spaces and control characters; "" for none. */
    public fun databaseName(name: String?): String = trimmed(name) ?: ""

    /**
     * The format (kdbx.new-file AC1, AC2): KDBX 4.0, [cipher], gzip, [kdf] with a fresh 32-byte salt (AES-KDF: seed)
     * from [random], its parameters as KeePass writes them (KeePass's own AES-KDF id; Argon2 version 0x13, memory in
     * bytes). No public custom data.
     */
    internal fun template(cipher: Cipher, kdf: Kdf, random: SecureRandom = SecureRandom()): KdbxTemplate {
        val cipherId = Header.CIPHERS.entries.firstOrNull { it.value == cipher && cipher != Cipher.TWOFISH }?.key
            ?: throw KdbxException.UnsupportedCipher(cipher.label)
        check(kdf)
        val salt = ByteArray(32).also(random::nextBytes)
        val parameters = VariantDictionary.Writer()
        when (kdf) {
            is Kdf.AesKdf -> parameters
                .bytes("\$UUID", Header.id(Header.KEEPASS_AES_KDF))
                .uint64("R", kdf.rounds)
                .bytes("S", salt)
            is Kdf.Argon2 -> parameters
                .bytes("\$UUID", Header.id(if (kdf.variant == Kdf.Argon2.Variant.D) Header.ARGON2D else Header.ARGON2ID))
                .uint32("V", 0x13)
                .bytes("S", salt)
                .uint32("P", kdf.parallelism.toLong())
                .uint64("M", kdf.memoryBytes)
                .uint64("I", kdf.iterations)
        }
        // Read back as the reader reads it: what it refuses is not written.
        val parsed = try {
            Header.kdf(parameters.finish()).first
        } catch (_: KdbxException.Corrupt) {
            throw KdbxException.UnsupportedKdf("Argon2 parameters")
        }
        return KdbxTemplate(4, 0, Header.id(cipherId), cipher, compressed = true, kdf = parsed, publicCustomData = null)
    }

    /** The first XML (kdbx.new-file AC5, AC6). */
    internal fun document(name: String?, fileName: String, now: Instant): XElement {
        val stamp = KdbxTime.base64(now)
        val none = EntryLifecycle.NULL_UUID
        val meta = XElement("Meta")
        for (child in listOf(
            XElement("Generator", GENERATOR),
            XElement("DatabaseName", databaseName(name)), XElement("DatabaseNameChanged", stamp),
            XElement("DatabaseDescription"), XElement("DatabaseDescriptionChanged", stamp),
            XElement("DefaultUserName"), XElement("DefaultUserNameChanged", stamp),
            XElement("MaintenanceHistoryDays", "365"),
            XElement("Color"),
            XElement("MasterKeyChanged", stamp), XElement("MasterKeyChangeRec", "-1"), XElement("MasterKeyChangeForce", "-1"),
            XElement("MemoryProtection").apply {
                for ((field, protected) in listOf("Title" to false, "UserName" to false, "Password" to true, "URL" to false, "Notes" to false)) {
                    append(XElement("Protect$field", if (protected) "True" else "False"))
                }
            },
            XElement("CustomIcons"),
            XElement("RecycleBinEnabled", "True"), XElement("RecycleBinUUID", none), XElement("RecycleBinChanged", stamp),
            XElement("EntryTemplatesGroup", none), XElement("EntryTemplatesGroupChanged", stamp),
            XElement("LastSelectedGroup", none), XElement("LastTopVisibleGroup", none),
            XElement("HistoryMaxItems", "10"), XElement("HistoryMaxSize", "6291456"),
            XElement("CustomData"),
        )) meta.append(child)

        val group = XElement("Group")
        for (child in listOf(
            XElement("UUID", Entry.newId()),
            XElement("Name", rootGroupName(name, fileName)),
            XElement("Notes"),
            XElement("IconID", "48"),
            EntryLifecycle.newTimes(now, iso = false),
            XElement("IsExpanded", "True"),
            XElement("DefaultAutoTypeSequence"),
            XElement("EnableAutoType", "null"),
            XElement("EnableSearching", "null"),
            XElement("LastTopVisibleEntry", none),
        )) group.append(child)

        val root = XElement("Root").apply {
            append(group)
            append(XElement("DeletedObjects"))
        }
        return XElement("KeePassFile").apply {
            append(meta)
            append(root)
        }
    }

    /**
     * Without its outer spaces, control characters (a line break, a tab: XML 1.0 cannot hold most of them, and a name
     * is one line) and what else XML cannot hold (a lone surrogate, U+FFFE, U+FFFF); null when nothing is left.
     */
    private fun trimmed(text: String?): String? {
        val kept = StringBuilder()
        text?.codePoints()?.forEach { point ->
            val drop = Character.getType(point) == Character.CONTROL.toInt() || point in 0xD800..0xDFFF || point == 0xFFFE || point == 0xFFFF
            if (!drop) kept.appendCodePoint(point)
        }
        return kept.toString().trim().takeIf { it.isNotEmpty() }
    }

}
