package dev.keething.vault.creating

import dev.keething.domain.storage.GrantedFolder
import dev.keething.domain.storage.NewFile
import dev.keething.domain.storage.NewFilePlace
import dev.keething.domain.storage.StoragePlace
import dev.keething.vault.databases.FileLocation
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * What a new database's Create is making, kept in the app's own storage while it runs (keething-spec
 * databases.new-database-create AC5; plan AD7, decided at its start): the process can end in the middle (the system
 * ends it, a crash, a short foreground service that did not hold), and a key file without its database, or a database
 * that never joined the list, would be left behind. At the next start [leftovers] removes what the note names that
 * Create made — only a file still empty or byte for byte as written, never one someone else put there — and says the
 * database's name. A file is noted by where it is: its path, or its grant and its path inside the granted folder, which
 * [folders] reaches again (null: the grant is gone) (keething-spec databases.saf AC11). Blocking file I/O: call it off the
 * main thread.
 */
public class CreationNote(private val file: File, private val folders: (grant: String) -> GrantedFolder? = { null }) {
    /**
     * Creating [name] begins: a section of its own, after what an earlier Create of this process left noted (a file it
     * could not remove), which stays for the next start.
     */
    @Synchronized
    public fun begin(name: String) {
        write(read().orEmpty() + (NAME + escape(name)))
    }

    /** [file] is about to be made with [bytes]: noted first, so a process ended while it is written still finds it. */
    @Synchronized
    public fun making(file: FileLocation, bytes: ByteArray) {
        val lines = read() ?: return
        write(lines + listOf(line(Noted(file, digest(bytes), bytes.size.toLong()))))
    }

    /** [file] was not made after all (a file was there), or was removed again: nothing to remove at the next start. */
    @Synchronized
    public fun notMade(file: FileLocation) {
        val lines = read() ?: return
        write(lines.filterNot { noted(it)?.file == file })
    }

    /**
     * This Create is over. [made]: its files are the user's now, its section goes. Not made: what it made was removed
     * (and taken out of the note) or could not be; its section goes when nothing of it is left, else it stays for the
     * next start.
     */
    @Synchronized
    public fun end(made: Boolean) {
        val lines = read() ?: return
        val start = lines.indexOfLast { it.startsWith(NAME) }.coerceAtLeast(0)
        val section = lines.subList(start, lines.size)
        val kept = if (made || section.none { it.startsWith(MADE) }) lines.subList(0, start) else lines
        if (kept.isEmpty()) file.delete() else write(kept)
    }

    /** What an ended Create left: the database's [name] (the last one not made; empty when the note had none), and the files noted that are still there. */
    public data class Leftover(val name: String, val kept: List<FileLocation>)

    /**
     * A note an ended process left: the files it names that are still as Create wrote them (or empty: ended between
     * making the file and writing it) are removed, and the note goes. What could not be told for Create's (written in
     * part, changed since) or could not be removed stays and is said in [Leftover.kept]. A Create whose files are
     * [listed] (its database joined the list, and the process ended before the note did) was made: its files stay and
     * nothing is said of it. Null when there was no note, or nothing to say.
     */
    @Synchronized
    public fun leftovers(listed: (FileLocation) -> Boolean = { false }): Leftover? {
        val lines = read() ?: return null
        var name: String? = null
        val kept = mutableListOf<FileLocation>()
        for (section in sections(lines)) {
            if (section.files.any { listed(it.file) }) continue
            name = section.name
            for (noted in section.files) {
                if (!removeIfMine(noted)) kept += noted.file
            }
        }
        file.delete()
        return name?.let { Leftover(it, kept) }
    }

    /**
     * Removes [noted]'s file when it is still as Create wrote it, or empty. False when a file is there that could not be
     * told for Create's or could not be removed (its grant gone too).
     */
    private fun removeIfMine(noted: Noted): Boolean = when (val at = noted.file) {
        is FileLocation.ByPath -> {
            val made = File(at.path)
            !made.isFile || run {
                val mine = made.length() == 0L || made.length() == noted.size && runCatching { digest(made.readBytes()) }.getOrNull() == noted.digest
                // Read-only (a key file) is no obstacle to removing it from its folder.
                mine && made.delete()
            }
        }
        is FileLocation.InFolder -> try {
            val folder = folders(at.grant) ?: throw IOException("the folder's grant is gone")
            val bytes = folder.read(at.path)?.bytes
            bytes == null || (bytes.isEmpty() || bytes.size.toLong() == noted.size && digest(bytes) == noted.digest) && folder.delete(at.path)
        } catch (_: IOException) {
            false
        }
        // A new file is never made through a grant to that file alone.
        is FileLocation.Granted -> false
    }

    private class Section(val name: String, val files: MutableList<Noted> = mutableListOf())

    private class Noted(val file: FileLocation, val digest: String, val size: Long)

    /** Each Create's name and files, in order; files noted before any name are a section without one. */
    private fun sections(lines: List<String>): List<Section> {
        val sections = mutableListOf<Section>()
        for (line in lines) {
            if (line.startsWith(NAME)) {
                sections += Section(unescape(line.removePrefix(NAME)))
            } else {
                val noted = noted(line) ?: continue
                (sections.lastOrNull() ?: Section("").also(sections::add)).files += noted
            }
        }
        return sections
    }

    /**
     * A made file's line: `made <path>` (by path), `made-in <grant> <volume> <folder> <path>` (in a granted folder),
     * then the SHA-256 and the size, separated by tabs.
     */
    private fun line(noted: Noted): String {
        val at = when (val file = noted.file) {
            is FileLocation.ByPath -> MADE + escape(file.path)
            is FileLocation.InFolder -> MADE_IN + listOf(file.grant, file.folder.volume, file.folder.path, file.path).joinToString(SEPARATOR, transform = ::escape)
            is FileLocation.Granted -> throw IllegalArgumentException("a new file is never made through a grant to that file alone")
        }
        return at + SEPARATOR + noted.digest + SEPARATOR + noted.size
    }

    /** The made file a line names; null for a name's line, or one that cannot be read. */
    private fun noted(line: String): Noted? {
        val (parts, file) = when {
            line.startsWith(MADE) -> line.removePrefix(MADE).split(SEPARATOR).let { parts ->
                if (parts.size != 3) return null
                parts.drop(1) to FileLocation.ByPath(unescape(parts[0]))
            }
            line.startsWith(MADE_IN) -> line.removePrefix(MADE_IN).split(SEPARATOR).map(::unescape).let { parts ->
                if (parts.size != 6) return null
                parts.drop(4) to FileLocation.InFolder(parts[0], StoragePlace(parts[1], parts[2]), parts[3])
            }
            else -> return null
        }
        return Noted(file, parts[0], parts[1].toLongOrNull() ?: return null)
    }

    /** [target] made at [file] with what it is about to write noted first. */
    public fun noting(target: NewFile, file: FileLocation): NewFile = object : NewFile {
        override val fileName: String get() = target.fileName

        override fun check(): NewFilePlace = target.check()

        override fun create(bytes: ByteArray, readOnly: Boolean) {
            making(file, bytes)
            try {
                target.create(bytes, readOnly)
            } catch (failure: Exception) {
                // Refused or failed: [target] left nothing, or what is there is not this one's.
                notMade(file)
                throw failure
            }
        }

        override fun removeCreated() {
            target.removeCreated()
            notMade(file)
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
        const val MADE_IN = "made-in "
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
