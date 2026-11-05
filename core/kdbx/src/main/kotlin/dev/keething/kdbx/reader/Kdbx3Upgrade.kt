package dev.keething.kdbx.reader

import dev.keething.kdbx.tree.XElement
import dev.keething.kdbx.tree.XText
import dev.keething.kdbx.xml.KdbxTime

/**
 * A KDBX 3.1 file's XML put the way KDBX 4.0 holds it (keething-spec kdbx.kdbx3 AC8; KeePass's `KdbxFile.Write` for
 * version 4): the attachments leave `Meta/Binaries` for the inner header, an entry's `Ref` (the attachment's `ID`)
 * becoming its place there; `Meta/HeaderHash` goes (the header's SHA-256 and HMAC follow a KDBX 4 header); the times
 * become base64 of the seconds, as KeePass 2.x reads them in KDBX 4. Everything else stays as it was.
 */
internal object Kdbx3Upgrade {
    /**
     * Changes [root] in place; [attachments]: how many `Meta/Binaries` the reader read. The attachments' flags in their
     * order (0x01: protected in memory, as `Protected="True"` was).
     */
    fun toKdbx4(root: XElement, attachments: Int): List<Byte> {
        val meta = root.first("Meta")
        val places = HashMap<Int, Int>()
        val flags = ArrayList<Byte>()
        meta?.first("Binaries")?.let { binaries ->
            for (binary in binaries.all("Binary")) {
                val id = binary.attribute("ID")?.trim()?.toIntOrNull() ?: throw KdbxException.Corrupt("attachment without an ID")
                places[id] = flags.size
                flags += if (binary.secret != null) 1.toByte() else 0.toByte()
            }
            binaries.wipeSecrets()
            remove(binaries, meta)
        }
        if (flags.size != attachments) throw KdbxException.Corrupt("attachments")
        meta?.first("HeaderHash")?.let { remove(it, meta) }
        root.first("Root")?.let { convert(it, places) }
        convert(meta ?: return flags, places)
        return flags
    }

    /** Refs to places and times to base64, in [element] and everything inside it. */
    private fun convert(element: XElement, places: Map<Int, Int>) {
        if (element.name == "Binary") {
            element.first("Value")?.let { value ->
                val ref = value.attribute("Ref") ?: return@let
                val place = ref.trim().toIntOrNull()?.let(places::get) ?: throw KdbxException.Corrupt("attachment $ref missing")
                value.setAttribute("Ref", place.toString())
            }
        }
        val children = element.elements
        if (children.isEmpty()) {
            if (element.secret == null && (element.name.endsWith("Time") || element.name.endsWith("Changed")) && KdbxTime.isIso(element.text)) {
                KdbxTime.parse(element.text)?.let { element.text = KdbxTime.base64(it) }
            }
            return
        }
        children.forEach { convert(it, places) }
    }

    /** Removes [element] from [parent], with the whitespace that put it on its own line. */
    private fun remove(element: XElement, parent: XElement) {
        val index = parent.children.indexOfFirst { it === element }
        val before = parent.children.getOrNull(index - 1)
        if (before is XText && before.text.isBlank()) parent.children.removeAt(index - 1)
        parent.remove(element)
    }
}
