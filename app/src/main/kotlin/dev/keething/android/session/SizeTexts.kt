package dev.keething.android.session

import dev.keething.android.R
import dev.keething.android.ui.UiText
import dev.keething.android.ui.uiPlural
import dev.keething.android.ui.uiText
import dev.keething.vault.opening.DatabaseSize
import dev.keething.vault.session.AttachmentQuestion
import dev.keething.vault.session.SaveRoom

/**
 * What the app says of a database too large to save in the memory the phone gives it, or near that (keething-spec
 * app.large-database AC3): how large it is, whether its attachments make it so, and what to do.
 */
internal object SizeTexts {
    /**
     * The list's banner while it is open read-only: said as long as it is shown. Beside others, what to do is to lock
     * them (making it smaller may not be what it needs).
     */
    fun tooLarge(fileName: String, size: DatabaseSize, alone: Boolean): UiText = when {
        alone && size.isAttachments -> uiText(R.string.session_size_too_large_attachments, fileName, mib(size.fileBytes), mib(size.attachmentBytes))
        alone -> uiText(R.string.session_size_too_large_entries, fileName, mib(size.fileBytes))
        size.isAttachments -> uiText(R.string.session_size_too_large_beside_attachments, fileName, mib(size.fileBytes), mib(size.attachmentBytes))
        else -> uiText(R.string.session_size_too_large_beside_entries, fileName, mib(size.fileBytes))
    }

    /**
     * The list's banner once it became changeable, the others beside it locked (keething-spec app.large-database AC3):
     * said until OK. [near]: its save is near the limit all the same.
     */
    fun changeableAgain(fileName: String, near: Boolean): UiText =
        uiText(if (near) R.string.session_size_changeable_again_near else R.string.session_size_changeable_again, fileName)

    /** The list's banner once at an unlock near the limit; it still saves. */
    fun nearLimit(fileName: String, size: DatabaseSize): UiText = if (size.isAttachments) {
        uiText(R.string.session_size_near_limit_attachments, fileName, mib(size.fileBytes), mib(size.attachmentBytes))
    } else {
        uiText(R.string.session_size_near_limit_entries, fileName, mib(size.fileBytes))
    }

    /** A change tried on it before anything is typed (Edit, New). */
    fun refused(name: String, room: SaveRoom.TooLarge): UiText =
        uiText(if (room.alone) R.string.session_size_refused else R.string.session_size_refused_beside, name)

    /** A save refused because it is read-only ([dev.keething.vault.saving.SaveFailure.TooLarge]). */
    fun saveRefused(alone: Boolean): UiText =
        uiText(if (alone) R.string.session_size_save_refused else R.string.session_size_save_refused_beside)

    /**
     * Asked before [name] is attached (keething-spec app.large-database AC4): how large it is ([size], in the system's
     * units as the editor's rows show it), what it does to the database [fileName] (its room with it,
     * [AttachmentQuestion.room], when worse), and the same advice. The user may attach it.
     */
    fun largeAttachment(name: String, size: String, question: AttachmentQuestion, fileName: String): UiText {
        val text = when (val room = question.room) {
            SaveRoom.Near -> R.string.session_size_attachment_near
            is SaveRoom.TooLarge -> if (room.alone) R.string.session_size_attachment_too_large else R.string.session_size_attachment_too_large_beside
            SaveRoom.Enough, null -> R.string.session_size_attachment
        }
        return uiText(text, name, size, fileName)
    }

    /** A save that ran out of memory all the same. */
    val OUT_OF_MEMORY: UiText = uiText(R.string.session_size_out_of_memory)

    /** Its attachments make it so: half of it or more. */
    private val DatabaseSize.isAttachments: Boolean get() = attachmentBytes * 2 >= fileBytes

    /** Whole MiB, at least 1. */
    fun mib(bytes: Long): UiText = maxOf(1, (bytes + MIB / 2) / MIB).toInt().let { uiPlural(R.plurals.session_size_mib, it) }

    private const val MIB = 1L shl 20
}
