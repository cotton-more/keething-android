package dev.keething.kdbx.editing

import dev.keething.domain.secret.Secret
import dev.keething.kdbx.tree.XElement
import java.security.MessageDigest

/**
 * The file's attachments (the inner header's binaries with their flags) and the entries' references to them,
 * `<Binary><Value Ref="n"/>` in current entries and their history (port of the Mac `AttachmentPool`). As KeePassXC
 * does on every save: identical contents are stored once, and only what something still refers to is kept (an
 * attachment of a history version trimmed away goes).
 */
internal class AttachmentPool(binaries: List<Secret>, flags: List<Byte>) {
    var binaries: List<Secret> = binaries
        private set
    var flags: List<Byte> = flags
        private set

    /**
     * Drops the attachments nothing refers to, stores identical contents once, and renumbers the references; the
     * rest keep their order. Leaves everything as it is when a reference is not a valid index (nothing to
     * renumber safely). Dropped attachments are not wiped here: they are the caller's.
     */
    fun compact(root: XElement) {
        val refs = references(root)
        val indexes = refs.map { it.attribute("Ref")?.trim()?.toIntOrNull() }
        if (flags.size != binaries.size || indexes.any { it == null || it !in binaries.indices }) return

        // The first of identical contents stands for all of them.
        val firstByDigest = HashMap<String, Int>()
        val canonical = binaries.mapIndexed { index, binary ->
            val digest = digest(binary)
            val first = firstByDigest[digest]
            if (first != null && binaries[first].hasSameBytes(binary)) first else index.also { firstByDigest.putIfAbsent(digest, it) }
        }
        val used = indexes.map { canonical[it!!] }.toSortedSet().toList()
        if (used == binaries.indices.toList()) return
        val renumbered = used.withIndex().associate { (new, old) -> old to new }
        refs.zip(indexes).forEach { (value, index) -> value.setAttribute("Ref", renumbered.getValue(canonical[index!!]).toString()) }
        flags = used.map { flags[it] }
        binaries = used.map { binaries[it] }
    }

    /** What each reference points to, in document order: a digest per reference; null when not a valid index. */
    fun digests(root: XElement): List<String?> = references(root).map { value ->
        value.attribute("Ref")?.trim()?.toIntOrNull()?.let(binaries::getOrNull)?.let(::digest)
    }

    companion object {
        /** Every `Value` element that refers to an attachment, in document order. */
        fun references(root: XElement): List<XElement> {
            val found = mutableListOf<XElement>()
            fun walk(element: XElement) {
                for (child in element.elements) {
                    if (element.name == "Entry" && child.name == "Binary") {
                        child.first("Value")?.takeIf { it.attribute("Ref") != null }?.let(found::add)
                    } else {
                        walk(child)
                    }
                }
            }
            root.first("Root")?.let(::walk)
            return found
        }

        private fun digest(binary: Secret): String = binary.useBytes { bytes ->
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        }
    }
}
