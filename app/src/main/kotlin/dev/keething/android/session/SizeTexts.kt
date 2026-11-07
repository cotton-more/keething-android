package dev.keething.android.session

import dev.keething.vault.opening.DatabaseSize
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
    fun tooLarge(fileName: String, size: DatabaseSize, alone: Boolean): String = if (alone) {
        "This phone does not give Keething enough memory to save $fileName safely, so it is open to read only: you can " +
            "read and copy from it, not change it. ${size(size)} ${advice(size)}"
    } else {
        "With the other open databases beside it, this phone does not give Keething enough memory to save $fileName " +
            "safely, so it is open to read only: you can read and copy from it, not change it. ${size(size)} Lock the " +
            "others to change it: it becomes changeable once a save fits."
    }

    /**
     * The list's banner once it became changeable, the others beside it locked (keething-spec app.large-database AC3):
     * said until OK. [near]: its save is near the limit all the same.
     */
    fun changeableAgain(fileName: String, near: Boolean): String =
        "With the other databases locked, this phone has the memory to save $fileName: you can change it again." +
            if (near) " A save takes most of that memory, so keep the others locked while you change it." else ""

    /** The list's banner once at an unlock near the limit; it still saves. */
    fun nearLimit(fileName: String, size: DatabaseSize): String =
        "A save of $fileName takes most of the memory this phone gives Keething. It saves now, but not much larger. " +
            "${size(size)} ${advice(size)}"

    /** A change tried on it before anything is typed (Edit, New). */
    fun refused(name: String, room: SaveRoom.TooLarge): String =
        "“$name” is open to read only: " + if (room.alone) "too large for this phone to save safely." else
            "with the other databases open, too large for this phone to save safely. Lock them to change it."

    /** A save refused because it is read-only ([dev.keething.vault.saving.SaveFailure.TooLarge]). */
    fun saveRefused(alone: Boolean): String =
        "This database is open to read only: too large for this phone to save safely. Nothing was saved." +
            if (alone) "" else " Lock the other databases to change it."

    /** A save that ran out of memory all the same. */
    const val OUT_OF_MEMORY: String =
        "Not enough memory to write the database. Nothing was saved. Lock the other open databases and try again; " +
            "if it still fails, keep large files outside the database or split it into two."

    private fun size(size: DatabaseSize): String {
        val total = "It is ${mib(size.fileBytes)}"
        return if (size.attachmentBytes * 2 >= size.fileBytes) "$total; its attachments are ${mib(size.attachmentBytes)}." else "$total."
    }

    private fun advice(size: DatabaseSize): String =
        if (size.attachmentBytes * 2 >= size.fileBytes) {
            "To make it smaller, keep large files outside the database (remove the attachments in KeePassXC on a computer) " +
                "or split it into two databases."
        } else {
            "To make it smaller, split it into two databases, or remove old history in KeePassXC on a computer."
        }

    /** Whole MiB, at least 1. */
    fun mib(bytes: Long): String = "${maxOf(1, (bytes + MIB / 2) / MIB)} MiB"

    private const val MIB = 1L shl 20
}
