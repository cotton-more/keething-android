package dev.keething.android.storage

import dev.keething.domain.storage.BackupDamagedException
import dev.keething.domain.storage.DatabaseBackups
import dev.keething.vault.storage.BackupCheck
import java.io.File
import java.io.FileNotFoundException
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
 * before, a process ended before it was written) is read once and gets one. A copy restored must still be those bytes
 * ([read]).
 *
 * The file before a restore is kept whatever the switch says, marked by its name (`… (before restoring).kdbx`), so the
 * mark stays with the copy whatever else is lost; otherwise it is a copy like the others, pushed out in its turn
 * (keething-spec databases.backups-window AC6).
 */
internal class FileDatabaseBackups(
    private val folder: File,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val count: () -> Int = { 10 },
    private val isOn: () -> Boolean = { true },
    private val isDamaged: (File) -> Boolean = { file -> file.inputStream().use(BackupCheck::isDamaged) },
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

    /**
     * A copy as the database's page lists it: its file's [name], when it was written, its size, and whether its header
     * shows it would not open (keething-spec databases.backups-window AC1, AC5).
     */
    data class Backup(val name: String, val at: Instant, val bytes: Long, val damaged: Boolean, val beforeRestoring: Boolean = false)

    override val on: Boolean get() = isOn()

    override fun keep(database: ByteArray, fileName: String, at: Instant) {
        write(fileName, at) { it.write(database) }
    }

    override fun keep(database: InputStream, fileName: String, at: Instant) {
        write(fileName, at) { database.copyTo(it, 64 * 1024) }
    }

    override fun keepBeforeRestoring(database: InputStream, fileName: String, at: Instant): String =
        write(fileName, at, beforeRestoring = true) { database.copyTo(it, 64 * 1024) }.name

    @Synchronized
    private fun write(fileName: String, at: Instant, beforeRestoring: Boolean = false, writing: (DigestOutputStream) -> Unit): File {
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("backup folder")
        val stem = fileName.substringBeforeLast('.')
        val stamp = STAMP.format(at.atZone(zone))
        // Two saves within a second (a new entry, then deleting it): "_2", "_3"… sort after the first. After the last one
        // there is, not in a gap the pruning left: that name would sort first and go at once. Marked or not, one number.
        val numbers = listOf("") + (2..99).map { "_$it" }
        val taken = numbers.indexOfLast { File(folder, "$stem $stamp$it.kdbx").exists() || File(folder, "$stem $stamp$it$MARK.kdbx").exists() }
        val number = numbers.getOrNull(taken + 1) ?: throw IOException("backup: too many saves within a second")
        val target = File(folder, "$stem $stamp$number${if (beforeRestoring) MARK else ""}.kdbx")
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
        return target
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
    override fun restored(restoredName: String) {
        val copy = kept
        val sha256 = keptDigest
        kept = null
        keptDigest = null
        if (copy != null && sha256 != null) digests()[copy.name] = Digest(copy.length(), sha256)
        // Off, the copies kept stay until deleted. On, the newest stay as after a save, but the one restored stays whatever
        // its age, a backup the user chose: it counts among them, and the oldest of the others go in its place.
        if (isOn()) {
            val all = newestFirst()
            val others = all.filter { it.name != restoredName }
            others.drop(if (others.size < all.size) count() - 1 else count()).forEach(File::delete)
        }
        writeDigests()
    }

    @Synchronized
    override fun notSaved() {
        kept?.delete()
        kept = null
        keptDigest = null
    }

    @Synchronized
    override fun holding(sha256: ByteArray): String? {
        val known = digests()
        var added = false
        var found: String? = null
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
                found = file.name
                break
            }
        }
        if (added) writeDigests()
        return found
    }

    /**
     * The copy named [name], whole (keething-spec databases.backups-window AC6): only a backup's name in this folder;
     * null when it is not there. Its bytes must be those kept, by its line in [DIGESTS] ([BackupDamagedException]
     * otherwise); one without a line gets one now.
     */
    @Synchronized
    override fun read(name: String): ByteArray? {
        if (order(name) == null || name.contains('/')) return null
        val file = File(folder, name)
        if (!file.isFile) return null
        val bytes = try {
            file.readBytes()
        } catch (_: FileNotFoundException) {
            return null
        }
        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
        val known = digests()[name]
        if (known == null) {
            digests()[name] = Digest(bytes.size.toLong(), sha256)
            writeDigests()
        } else if (known.size != bytes.size.toLong() || !MessageDigest.isEqual(known.sha256, sha256)) {
            throw BackupDamagedException("$name is not the copy kept")
        }
        return bytes
    }

    /** How many copies there are and their size together. */
    @Synchronized
    fun summary(): Summary = backups().let { files -> Summary(files.size, files.sumOf(File::length)) }

    /**
     * The copies, newest first, each with its header checked (keething-spec databases.backups-window AC1, AC5): one that
     * cannot be read counts as damaged.
     */
    @Synchronized
    fun list(): List<Backup> = newestFirst().map { file ->
        val damaged = try {
            isDamaged(file)
        } catch (_: IOException) {
            true
        }
        Backup(file.name, Instant.ofEpochMilli(file.lastModified()), file.length(), damaged, beforeRestoring = isBeforeRestoring(file.name))
    }

    /** Only the newest [keep] stay; how many went (keething-spec databases.backups-window AC2). */
    @Synchronized
    fun prune(keep: Int): Int {
        val gone = newestFirst().drop(keep).count(File::delete)
        writeDigests()
        return gone
    }

    /**
     * The copy named [name] goes (keething-spec databases.backups-window AC4); only a backup's name in this folder.
     * Whether it went; one gone already did not.
     */
    @Synchronized
    fun delete(name: String): Boolean {
        if (order(name) == null || name.contains('/')) return false
        val gone = File(folder, name).delete()
        writeDigests()
        return gone
    }

    /** Every copy goes, files that are not copies stay (keething-spec databases.backups-window AC3); how many went. */
    @Synchronized
    fun deleteAll(): Int = prune(0)

    /**
     * Newest first by when they were written, then by name: a name's local time sorts wrong after the time zone changed
     * (a flight west, the fall back from summer time), and a new copy would go at once (kdbx.backups AC3).
     */
    private fun newestFirst(): List<File> = backups()
        .map { file -> Triple(file, file.lastModified(), order(file.name)!!) }
        .sortedWith(compareByDescending<Triple<File, Long, String>> { it.second }.thenByDescending { it.third })
        .map { it.first }

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

        /** What a copy's name ends with before `.kdbx` when it is the file before a restore. */
        private const val MARK = " (before restoring)"

        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH-mm-ss")
        private val BACKUP = Regex(".* ([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}-[0-9]{2}-[0-9]{2})(?:_([2-9]|[1-9][0-9]))?( \\(before restoring\\))?\\.kdbx")

        /**
         * A backup's name, `<name> 2026-10-26T14-03-22.kdbx` or `…_2.kdbx`, either with ` (before restoring)` before
         * `.kdbx`: its time and number in an order that sorts as they were made; null when it is not a backup (the
         * Mac's `backupOrder`).
         */
        internal fun order(fileName: String): String? {
            val match = BACKUP.matchEntire(fileName) ?: return null
            val number = match.groupValues[2].ifEmpty { "1" }.toInt()
            return "${match.groupValues[1]}_%02d".format(number)
        }

        /** Whether the backup named [fileName] is the file as it was before a restore (keething-spec databases.backups-window AC6). */
        internal fun isBeforeRestoring(fileName: String): Boolean = BACKUP.matchEntire(fileName)?.groupValues?.get(3)?.isNotEmpty() == true

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
