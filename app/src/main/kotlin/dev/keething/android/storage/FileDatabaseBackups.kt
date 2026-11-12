package dev.keething.android.storage

import dev.keething.domain.storage.DatabaseBackups
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * One database's copies as it was before a save (SPEC §3.3 step 7; the Mac `KDBXFileSaver.backup`): in a folder of
 * its own in the app's storage (`filesDir/backups/<database id>`, never backed up off the phone: the app does not allow
 * backups), named after the database and the local time; the newest [count] stay, newest by when they were written. The folder is the database's, so
 * two `Personal.kdbx` in two folders keep theirs apart, and a database located under another name keeps its older
 * ones; files in it that are not backups are left alone. The oldest go only once a save replaced the database
 * ([saved]); a save that did not takes its own copy back ([notSaved]), so it pushes no older one out (keething-spec
 * kdbx.hardening AC9). [on] and [count] are the database's settings, read when they are needed (databases.backups-window
 * AC2). One object a database, the session's and its page's alike, so a save and the page never prune at once.
 *
 * Each copy's SHA-256 is kept in [DIGESTS] beside them (`<hex> <size> <name>` a line), written as a copy is kept, so
 * whether a copy has the same bytes is known without reading them all ([holds]); a copy without its line (one of
 * before, a process ended before it was written) is read once and gets one.
 */
internal class FileDatabaseBackups(
    private val folder: File,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val count: () -> Int = { 10 },
    private val isOn: () -> Boolean = { true },
) : DatabaseBackups {
    /** The copy the running save kept, until it says how it went. */
    private var kept: File? = null

    /** Its SHA-256, written down once the save replaced the database. */
    private var keptDigest: ByteArray? = null

    /** What [DIGESTS] holds, by the copy's name; read at the first need. */
    private var digests: MutableMap<String, Digest>? = null

    private class Digest(val size: Long, val sha256: ByteArray)

    /** How many copies there are and their bytes together. */
    data class Summary(val count: Int, val bytes: Long)

    override val on: Boolean get() = isOn()

    override fun keep(database: ByteArray, fileName: String, at: Instant) {
        write(fileName, at) { it.write(database) }
    }

    override fun keep(database: InputStream, fileName: String, at: Instant) {
        write(fileName, at) { database.copyTo(it, 64 * 1024) }
    }

    @Synchronized
    private fun write(fileName: String, at: Instant, writing: (DigestOutputStream) -> Unit) {
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("backup folder")
        val stem = fileName.substringBeforeLast('.')
        val stamp = STAMP.format(at.atZone(zone))
        // Two saves within a second (a new entry, then deleting it): "_2", "_3"… sort after the first. After the last one
        // there is, not in a gap the pruning left: that name would sort first and go at once.
        val names = (listOf("") + (2..99).map { "_$it" }).map { File(folder, "$stem $stamp$it.kdbx") }
        val target = names.getOrNull(names.indexOfLast { it.exists() } + 1) ?: throw IOException("backup: too many saves within a second")
        kept = target
        keptDigest = null
        val sha256 = MessageDigest.getInstance("SHA-256")
        FileOutputStream(target).use { file ->
            val out = DigestOutputStream(file, sha256)
            writing(out)
            out.flush()
            file.fd.sync()
        }
        keptDigest = sha256.digest()
    }

    @Synchronized
    override fun saved() {
        val copy = kept
        val sha256 = keptDigest
        kept = null
        keptDigest = null
        if (copy != null && sha256 != null) digests()[copy.name] = Digest(copy.length(), sha256)
        prune(count())
    }

    @Synchronized
    override fun notSaved() {
        kept?.delete()
        kept = null
        keptDigest = null
    }

    @Synchronized
    override fun holds(sha256: ByteArray): Boolean {
        val known = digests()
        var added = false
        var found = false
        for (file in backups()) {
            val size = file.length()
            val digest = known[file.name]?.takeIf { it.size == size } ?: try {
                Digest(size, digestOf(file)).also {
                    known[file.name] = it
                    added = true
                }
            } catch (_: IOException) {
                continue
            }
            if (MessageDigest.isEqual(digest.sha256, sha256)) {
                found = true
                break
            }
        }
        if (added) writeDigests()
        return found
    }

    /** How many copies there are and their size together. */
    @Synchronized
    fun summary(): Summary = backups().let { files -> Summary(files.size, files.sumOf(File::length)) }

    /** Only the newest [keep] stay; how many went (keething-spec databases.backups-window AC2). */
    @Synchronized
    fun prune(keep: Int): Int {
        // Newest first by when they were written, then by name: a name's local time sorts wrong after the time zone
        // changed (a flight west, the fall back from summer time), and a new copy would go at once (kdbx.backups AC3).
        val gone = backups()
            .map { file -> Triple(file, file.lastModified(), order(file.name)!!) }
            .sortedWith(compareByDescending<Triple<File, Long, String>> { it.second }.thenByDescending { it.third })
            .drop(keep)
            .count { it.first.delete() }
        writeDigests()
        return gone
    }

    /** Every copy goes, files that are not copies stay (keething-spec databases.backups-window AC3); how many went. */
    @Synchronized
    fun deleteAll(): Int = prune(0)

    /** The folder's copies: its files named as backups. */
    private fun backups(): List<File> = folder.listFiles().orEmpty().filter { it.isFile && order(it.name) != null }

    private fun digests(): MutableMap<String, Digest> = digests ?: readDigests().also { digests = it }

    /** [DIGESTS] as written; lines that do not read are left out (their copies are read again when needed). */
    private fun readDigests(): MutableMap<String, Digest> {
        val file = File(folder, DIGESTS)
        val lines = try {
            if (file.isFile) file.readLines() else emptyList()
        } catch (_: IOException) {
            emptyList()
        }
        val read = mutableMapOf<String, Digest>()
        for (line in lines) {
            val parts = line.split(' ', limit = 3)
            if (parts.size != 3 || parts[0].length != 64) continue
            val size = parts[1].toLongOrNull() ?: continue
            val sha256 = runCatching { parts[0].hexToByteArray() }.getOrNull() ?: continue
            read[parts[2]] = Digest(size, sha256)
        }
        return read
    }

    /**
     * [DIGESTS] written anew, only the copies there are, through a file renamed over it. Not flushed to the storage: a
     * line lost to a power cut only makes its copy read once more.
     */
    private fun writeDigests() {
        val known = digests()
        val names = backups().map(File::getName).toSet()
        known.keys.retainAll(names)
        if (!folder.isDirectory) return
        val text = known.entries.sortedBy { it.key }.joinToString("") { (name, digest) -> "${digest.sha256.toHexString()} ${digest.size} $name\n" }
        try {
            val temporary = File(folder, "$DIGESTS.tmp")
            temporary.writeText(text)
            if (!temporary.renameTo(File(folder, DIGESTS))) temporary.delete()
        } catch (_: IOException) {
            // Read again when needed.
        }
    }

    companion object {
        /** The copies' SHA-256, beside them; not a backup's name, so never pruned as one. */
        internal const val DIGESTS = ".sha256"

        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH-mm-ss")
        private val BACKUP = Regex(".* ([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}-[0-9]{2}-[0-9]{2})(?:_([2-9]|[1-9][0-9]))?\\.kdbx")

        /**
         * A backup's name, `<name> 2026-10-26T14-03-22.kdbx` or `…_2.kdbx`: its time and number in an order that sorts
         * as they were made; null when it is not a backup (the Mac's `backupOrder`).
         */
        internal fun order(fileName: String): String? {
            val match = BACKUP.matchEntire(fileName) ?: return null
            val number = match.groupValues[2].ifEmpty { "1" }.toInt()
            return "${match.groupValues[1]}_%02d".format(number)
        }

        private fun digestOf(file: File): ByteArray {
            val sha256 = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    sha256.update(buffer, 0, read)
                }
            }
            return sha256.digest()
        }
    }
}
