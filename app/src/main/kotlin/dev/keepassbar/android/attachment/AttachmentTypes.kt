package dev.keepassbar.android.attachment

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** The type another app is told an attachment has. */
internal object AttachmentTypes {
    const val GENERIC = "application/octet-stream"
    const val TEXT = "text/plain"

    /** Larger files without a known extension are not read through to tell text. */
    const val MAX_TEXT_CHECK = 1 shl 20

    /**
     * The type the extension tells ([byExtension]), else text when the bytes are text (an SSH key as
     * `id_ed25519`, recovery codes: the usual attachments without an extension), else a generic type.
     */
    fun forOpening(byExtension: String?, bytes: ByteArray): String =
        byExtension ?: if (isText(bytes)) TEXT else GENERIC

    /** Valid UTF-8 without control characters other than tabs and line breaks. */
    fun isText(bytes: ByteArray): Boolean {
        if (bytes.size > MAX_TEXT_CHECK) return false
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
        } catch (_: CharacterCodingException) {
            return false
        }
        return text.all { !it.isISOControl() || it == '\t' || it == '\n' || it == '\r' || it == '\u000C' }
    }
}
