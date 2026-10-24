package dev.keething.android.attachment

/** File names from attachment names: what another app or the save dialog is given. */
internal object AttachmentNames {
    /** The most a file name may take on Android's file systems, in UTF-8 bytes. */
    private const val MAX_BYTES = 255

    /**
     * A file name from an attachment name (Mac `AttachmentFiles.safeName`): no folders, no control
     * characters, never empty, short enough for the file system; the extension is kept when it is shortened.
     */
    fun fileName(name: String): String {
        val cleaned = name
            .replace('/', '-')
            .replace(':', '-')
            .filterNot(Char::isISOControl)
            .trim()
        if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") return "attachment"
        return shortened(cleaned)
    }

    /** The lower-case extension, without the dot; empty when there is none. */
    fun extension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0 || dot == name.length - 1) "" else name.substring(dot + 1).lowercase()
    }

    private fun shortened(name: String): String {
        if (name.utf8Size() <= MAX_BYTES) return name
        val extension = extension(name).takeIf { it.isNotEmpty() && it.length <= 16 }?.let { name.takeLast(it.length + 1) }.orEmpty()
        var base = name.dropLast(extension.length)
        while (base.isNotEmpty() && (base + extension).utf8Size() > MAX_BYTES) {
            // By code point: a surrogate pair is not cut in half.
            base = base.dropLast(if (base.length >= 2 && base[base.length - 1].isLowSurrogate()) 2 else 1)
        }
        return base.trimEnd() + extension
    }

    private fun String.utf8Size(): Int = encodeToByteArray().size
}
