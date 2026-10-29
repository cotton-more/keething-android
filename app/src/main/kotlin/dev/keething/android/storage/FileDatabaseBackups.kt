package dev.keething.android.storage

import dev.keething.domain.storage.DatabaseBackups
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * One database's copies as it was before each save (SPEC §3.3 step 7; the Mac `KDBXFileSaver.backup`): in a folder of
 * its own in the app's storage (`filesDir/backups/<database id>`, never backed up off the phone: the app does not allow
 * backups), named after the database and the local time; the newest [count] stay. The folder is the database's, so
 * two `Personal.kdbx` in two folders keep theirs apart, and a database located under another name keeps its older
 * ones; files in it that are not backups are left alone.
 */
internal class FileDatabaseBackups(
    private val folder: File,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val count: Int = 10,
) : DatabaseBackups {
    override fun keep(database: ByteArray, fileName: String, at: Instant) {
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("backup folder")
        val stem = fileName.substringBeforeLast('.')
        val stamp = STAMP.format(at.atZone(zone))
        // Two saves within a second (a new entry, then deleting it): "_2", "_3"… sort after the first. After the last one
        // there is, not in a gap the pruning left: that name would sort first and go at once.
        val names = (listOf("") + (2..99).map { "_$it" }).map { File(folder, "$stem $stamp$it.kdbx") }
        val target = names.getOrNull(names.indexOfLast { it.exists() } + 1) ?: throw IOException("backup: too many saves within a second")
        FileOutputStream(target).use { out ->
            out.write(database)
            out.fd.sync()
        }
        folder.list().orEmpty()
            .mapNotNull { name -> order(name)?.let { name to it } }
            .sortedByDescending { it.second }
            .drop(count)
            .forEach { File(folder, it.first).delete() }
    }

    companion object {
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
    }
}
