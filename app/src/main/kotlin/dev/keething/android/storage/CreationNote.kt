package dev.keething.android.storage

import dev.keething.domain.storage.NewFile
import dev.keething.domain.storage.NewFilePlace
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * What a new database's Create is making, kept in the app's own storage while it runs (keething-spec
 * databases.new-database-create AC5; plan AD7, decided at its start): the process can end in the middle (the system
 * ends it, a crash, a short foreground service that did not hold), and a key file without its database, or a database
 * that never joined the list, would be left behind. At the next start [leftovers] removes what the note names that
 * Create made — only a file still empty or byte for byte as written, never one someone else put there — and says the
 * database's name. Blocking file I/O: call it off the main thread.
 */
internal class CreationNote(private val file: File) {
    /**
     * Creating [name] begins: a section of its own, after what an earlier Create of this process left noted (a file it
     * could not remove), which stays for the next start.
     */
    @Synchronized
    fun begin(name: String) {
        write(read().orEmpty() + (NAME + escape(name)))
    }

    /** [path] is about to be made with [bytes]: noted first, so a process ended while it is written still finds it. */
    @Synchronized
    fun making(path: String, bytes: ByteArray) {
        val lines = read() ?: return
        write(lines + listOf(MADE + escape(path) + SEPARATOR + digest(bytes) + SEPARATOR + bytes.size))
    }

    /** [path] was not made after all (a file was there), or was removed again: nothing to remove at the next start. */
    @Synchronized
    fun notMade(path: String) {
        val lines = read() ?: return
        write(lines.filterNot { it.startsWith(MADE) && unescape(it.removePrefix(MADE).substringBefore(SEPARATOR)) == path })
    }

    /**
     * This Create is over. [made]: its files are the user's now, its section goes. Not made: what it made was removed
     * (and taken out of the note) or could not be; its section goes when nothing of it is left, else it stays for the
     * next start.
     */
    @Synchronized
    fun end(made: Boolean) {
        val lines = read() ?: return
        val start = lines.indexOfLast { it.startsWith(NAME) }.coerceAtLeast(0)
        val section = lines.subList(start, lines.size)
        val kept = if (made || section.none { it.startsWith(MADE) }) lines.subList(0, start) else lines
        if (kept.isEmpty()) file.delete() else write(kept)
    }

    /** What an ended Create left: the database's [name] (the last one not made; empty when the note had none), and the files noted that are still there. */
    data class Leftover(val name: String, val kept: List<String>)

    /**
     * A note an ended process left: the files it names that are still as Create wrote them (or empty: ended between
     * making the file and writing it) are removed, and the note goes. What could not be told for Create's (written in
     * part, changed since) or could not be removed stays and is said in [Leftover.kept]. A Create whose files are
     * [listed] (its database joined the list, and the process ended before the note did) was made: its files stay and
     * nothing is said of it. Null when there was no note, or nothing to say.
     */
    @Synchronized
    fun leftovers(listed: (String) -> Boolean = { false }): Leftover? {
        val lines = read() ?: return null
        var name: String? = null
        val kept = mutableListOf<String>()
        for (section in sections(lines)) {
            if (section.files.any { listed(it.path) }) continue
            name = section.name
            for (noted in section.files) {
                val made = File(noted.path)
                if (!made.isFile) continue
                val mine = made.length() == 0L || made.length() == noted.size && runCatching { digest(made.readBytes()) }.getOrNull() == noted.digest
                // Read-only (a key file) is no obstacle to removing it from its folder.
                if (!mine || !made.delete()) kept += made.path
            }
        }
        file.delete()
        return name?.let { Leftover(it, kept) }
    }

    private class Section(val name: String, val files: MutableList<Noted> = mutableListOf())

    private class Noted(val path: String, val digest: String, val size: Long)

    /** Each Create's name and files, in order; files noted before any name are a section without one. */
    private fun sections(lines: List<String>): List<Section> {
        val sections = mutableListOf<Section>()
        for (line in lines) {
            if (line.startsWith(NAME)) {
                sections += Section(unescape(line.removePrefix(NAME)))
            } else if (line.startsWith(MADE)) {
                val parts = line.removePrefix(MADE).split(SEPARATOR)
                val size = parts.getOrNull(2)?.toLongOrNull() ?: continue
                if (parts.size != 3) continue
                (sections.lastOrNull() ?: Section("").also(sections::add)).files += Noted(unescape(parts[0]), parts[1], size)
            }
        }
        return sections
    }

    /** [target] made at [path] with what it is about to write noted first. */
    fun noting(target: NewFile, path: String): NewFile = object : NewFile {
        override val fileName: String get() = target.fileName

        override fun check(): NewFilePlace = target.check()

        override fun create(bytes: ByteArray, readOnly: Boolean) {
            making(path, bytes)
            try {
                target.create(bytes, readOnly)
            } catch (failure: Exception) {
                // Refused or failed: [target] left nothing, or what is there is not this one's.
                notMade(path)
                throw failure
            }
        }

        override fun removeCreated() {
            target.removeCreated()
            notMade(path)
        }
    }

    private fun read(): List<String>? = try {
        if (file.isFile) file.readLines() else null
    } catch (_: IOException) {
        null
    }

    private fun write(lines: List<String>) {
        val next = File(file.parentFile, file.name + ".new")
        next.writeText(lines.joinToString("\n", postfix = "\n"))
        if (!next.renameTo(file)) throw IOException("the note of what is being created could not be written")
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val NAME = "name "
        const val MADE = "made "
        const val SEPARATOR = "\t"

        /** A tab or a line break in a name or a path stays one field of one line. */
        fun escape(text: String): String = text.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

        fun unescape(text: String): String {
            val out = StringBuilder()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '\\' && i + 1 < text.length) {
                    out.append(
                        when (text[i + 1]) {
                            't' -> '\t'
                            'n' -> '\n'
                            else -> text[i + 1]
                        },
                    )
                    i += 2
                } else {
                    out.append(c)
                    i++
                }
            }
            return out.toString()
        }
    }
}
