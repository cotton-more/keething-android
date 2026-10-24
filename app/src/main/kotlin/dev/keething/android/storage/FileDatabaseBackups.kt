package dev.keething.android.storage

import dev.keething.domain.storage.DatabaseBackups
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The database as it was before each save (SPEC §3.3 step 7; the Mac `KDBXFileSaver.backup`): in the app's own
 * storage (`filesDir/backups`, never backed up off the phone: the app does not allow backups), named after the
 * database and the local time; the newest [count] of each database stay.
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
        // Two saves within a second (a new entry, then deleting it): "_2", "_3"… sort after the first.
        val target = (listOf("") + (2..99).map { "_$it" })
            .map { File(folder, "$stem $stamp$it.kdbx") }
            .firstOrNull { !it.exists() } ?: throw IOException("backup: too many saves within a second")
        FileOutputStream(target).use { out ->
            out.write(database)
            out.fd.sync()
        }
        folder.list().orEmpty()
            .filter { it.startsWith("$stem ") && it.endsWith(".kdbx") }
            .sortedDescending()
            .drop(count)
            .forEach { File(folder, it).delete() }
    }

    private companion object {
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH-mm-ss")
    }
}
