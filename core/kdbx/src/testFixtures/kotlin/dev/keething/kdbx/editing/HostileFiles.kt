package dev.keething.kdbx.editing

import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.NewGroup
import dev.keething.domain.vault.VaultEdit
import dev.keething.kdbx.crypto.Bytes
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.kdbx.reader.KdbxReader
import dev.keething.kdbx.reader.VariantDictionary
import dev.keething.kdbx.tree.XElement
import dev.keething.kdbx.tree.XmlTree
import dev.keething.kdbx.writer.KdbxWriter
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant

/**
 * Files made to break a reader or a writer, as the review of the Mac's KDBX engine rebuilt them (keething-spec
 * features/kdbx/hardening/review.md, Appendix A): for the tests of kdbx.hardening.
 */
public object HostileFiles {
    /** The deepest nesting the app reads (`XmlTree.MAX_DEPTH`). */
    public const val MAX_DEPTH: Int = XmlTree.MAX_DEPTH

    /** Argon2's KDF parameters as a header holds them (KeePass's names: V, I, M in bytes, P, S). */
    public fun argon2(version: Long = 0x13, iterations: Long = 2, memoryBytes: Long = 64L shl 10, lanes: Long = 1, salt: ByteArray = ByteArray(32) { 7 }): ByteArray =
        VariantDictionary.Writer()
            .bytes("\$UUID", hex("ef636ddf8c29444b91f7a9a403e30a0c"))
            .uint32("V", version)
            .bytes("S", salt)
            .uint32("P", lanes)
            .uint64("M", memoryBytes)
            .uint64("I", iterations)
            .finish()

    /** AES-KDF's parameters: [rounds] as an unsigned 64-bit number (`-1` is 2⁶⁴−1). */
    public fun aesKdf(rounds: Long, seed: ByteArray = ByteArray(32) { 9 }): ByteArray =
        VariantDictionary.Writer().bytes("\$UUID", hex("c9d9f39a628a4460bf740d08c18a4fea")).uint64("R", rounds).bytes("S", seed).finish()

    /**
     * [file] with its header's KDF parameters (field 11) replaced by [parameters] and the header's SHA-256 made right
     * again (review M3): the HMAC that follows stays the old one, as in a file swapped in by someone without the key,
     * since the derivation runs before the HMAC can be checked.
     */
    public fun withKdfParameters(file: ByteArray, parameters: ByteArray): ByteArray {
        val header = ByteArrayOutputStream()
        header.write(file, 0, 12)
        var at = 12
        while (true) {
            val id = file[at].toInt() and 0xFF
            val size = Bytes.intLe(file, at + 1)
            val value = file.copyOfRange(at + 5, at + 5 + size)
            at += 5 + size
            val written = if (id == 11) parameters else value
            header.write(id)
            header.write(Bytes.le32(written.size))
            header.write(written)
            if (id == 0) break
        }
        val bytes = header.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(bytes)
        out.write(MessageDigest.getInstance("SHA-256").digest(bytes))
        out.write(file, at + 32, file.size - at - 32)
        return out.toByteArray()
    }

    /** [file] with the first entry's `Times/LastModificationTime` written as [time] (review M6: `AAAAAAAAAIA=`). Its UUID too. */
    public fun withEntryTime(file: ByteArray, key: DatabaseKey, time: String): Pair<ByteArray, String> = edited(file, key) { editable ->
        val entry = EntryLifecycle.locate(editable.document.entries.first().uuid, editable.root)!!.entry
        entry.first("Times")!!.first("LastModificationTime")!!.text = time
        EntryLifecycle.uuidText(entry)
    }

    /**
     * [file] with groups nested inside its top group until its deepest element is [depth] levels deep (KeePassFile
     * being the first; review M7), deeper than the app reads too: the writer has no limit. The innermost group's id too.
     */
    public fun nested(file: ByteArray, key: DatabaseKey, depth: Int): Pair<ByteArray, String> = edited(file, key) { editable ->
        var parent = EntryLifecycle.uuidText(EntryLifecycle.topGroup(editable.root)!!)
        var level = 0
        while (deepest(editable.root) < depth) {
            val id = Entry.newId()
            editable.apply(VaultEdit.CreateGroup(parent, NewGroup(id, "Level ${++level}")), Instant.ofEpochSecond(1_900_000_000))
            parent = id
        }
        check(deepest(editable.root) == depth) { "one group made it deeper than $depth" }
        parent
    }

    /** How deep the tree's deepest element is, [root] being 1. */
    internal fun deepest(root: XElement): Int {
        var most = 0
        val open = ArrayDeque(listOf(root to 1))
        while (open.isNotEmpty()) {
            val (element, depth) = open.removeLast()
            most = maxOf(most, depth)
            element.elements.forEach { open += it to depth + 1 }
        }
        return most
    }

    private fun <T> edited(file: ByteArray, key: DatabaseKey, change: (EditableDatabase) -> T): Pair<ByteArray, T> {
        val editable = KdbxReader.openForEditing(file, key)
        try {
            val result = change(editable)
            return KdbxWriter.write(editable.root, editable.binaries, editable.binaryFlags, editable.template, key) to result
        } finally {
            editable.wipe()
        }
    }

    private fun hex(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
