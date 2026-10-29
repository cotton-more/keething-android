package dev.keething.android.storageprobe

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import dev.keething.android.storage.PathDatabaseStore
import dev.keething.android.storage.PathNewFile
import dev.keething.domain.encryption.DatabaseEncryption
import dev.keething.domain.encryption.MeasuredEncryption
import dev.keething.domain.secret.Secret
import dev.keething.domain.storage.KeyFileRead
import dev.keething.domain.storage.KeyFileSource
import dev.keething.vault.creating.DatabaseCreator
import dev.keething.vault.creating.EncryptionMeasurement
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.DurationUnit

/**
 * The measurements for new databases (keething-spec plans/android-databases.md AD2), in the chosen folder: how a new
 * file can be put in place without replacing one, how long the Mac's 1 s database takes to open here, and what this
 * phone measures for 1 s. Fictitious files only (password `pw`); file metadata and timings are logged, nothing else.
 */
internal class NewFileProbe(private val newFileAt: (File) -> PathNewFile, private val log: (String) -> Unit) {
    /** `link(2)`, `O_EXCL`, symbolic links and the modification time of a new file, as the app's shared storage does them. */
    fun placement(folder: File) {
        val stamp = LocalTime.now().format(STAMP)
        val temporary = File(folder, PathDatabaseStore.temporaryFileName())
        val linked = File(folder, "keething-probe-link-$stamp.bin")
        temporary.writeBytes(ByteArray(1024) { it.toByte() })
        try {
            attempt("Files.createLink") { Files.createLink(linked.toPath(), temporary.toPath()) }
            if (!linked.exists()) attempt("Os.link") { Os.link(temporary.path, linked.path) }
            if (linked.exists()) {
                log("link: made, ${linked.length()} bytes; again over it:")
                attempt("Os.link over a file") { Os.link(temporary.path, linked.path) }
            }
        } finally {
            temporary.delete()
            linked.delete()
        }

        val exclusive = File(folder, "keething-probe-excl-$stamp.bin")
        attempt("CREATE_NEW") { Files.write(exclusive.toPath(), byteArrayOf(1), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE) }
        attempt("CREATE_NEW over it") { Files.write(exclusive.toPath(), byteArrayOf(2), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE) }
        log("excl: content now ${exclusive.readBytes().toList()}")
        exclusive.delete()

        val dangling = File(folder, "keething-probe-dangling-$stamp.kdbx")
        attempt("symlink") { Files.createSymbolicLink(dangling.toPath(), File(folder, "keething-probe-gone").toPath()) }
        if (Files.isSymbolicLink(dangling.toPath())) {
            val file = newFileAt(dangling)
            log("dangling link: check ${file.check()}")
            attempt("PathNewFile over a dangling link") { file.create(byteArrayOf(3)) }
            dangling.delete()
        }

        val made = File(folder, "keething-probe-new-$stamp.bin")
        val file = newFileAt(made)
        val bytes = ByteArray(4096) { (it * 7).toByte() }
        val started = System.nanoTime()
        attempt("PathNewFile") { file.create(bytes) }
        log("PathNewFile: ${elapsed(started)}, ${made.length()} bytes, same ${made.readBytes().contentEquals(bytes)}")
        attempt("PathNewFile again") { newFileAt(made).create(bytes) }
        val before = made.lastModified()
        made.inputStream().use { it.read() }
        val afterRead = made.lastModified()
        Thread.sleep(2_000)
        made.inputStream().use { it.read() }
        log("mtime: written ${Instant.ofEpochMilli(before)}, after a read ${Instant.ofEpochMilli(afterRead)}, 2 s later ${Instant.ofEpochMilli(made.lastModified())}")
        log("kept for Syncthing: ${made.name}")

        val keyFile = File(folder, "Probe $stamp.keyx")
        val readBack = object : KeyFileSource {
            override val fileName = keyFile.name
            override fun read(): KeyFileRead = KeyFileRead.Found(keyFile.readBytes())
        }
        val creator = DatabaseCreator(Instant::now)
        val key = creator.createKeyFile(newFileAt(keyFile), readBack)
        log("key file: ${keyFile.name}, ${keyFile.length()} bytes, read back; again over it:")
        attempt("createKeyFile again") { creator.createKeyFile(newFileAt(keyFile), readBack).wipe() }
        val withKey = File(folder, "Probe $stamp key file.kdbx")
        val password = Secret.of(PASSWORD)
        val measured = MeasuredEncryption(DatabaseEncryption.recommended(), units = 2, seconds = 0.1)
        creator.create(newFileAt(withKey), "Probe $stamp key file", password, key, measured).wipe()
        password.wipe()
        key.wipe()
        log("made ${withKey.name} with pw and the key file, ${withKey.length()} bytes")
        attempt("database again over it") {
            creator.create(newFileAt(withKey), null, Secret.of(PASSWORD), null, measured).wipe()
        }
    }

    /** The Mac's default (64 MiB, 4 lanes, 115 iterations, about 1 s there) opened here, three times. */
    fun timeMacDefault(folder: File) {
        val file = File(folder, MAC_DEFAULT)
        if (!file.exists()) return log("no $MAC_DEFAULT in ${folder.path}: copy it there from fixtures/created")
        val bytes = file.readBytes()
        repeat(3) { run ->
            val took = EncryptionMeasurement.keyDerivationTime(bytes, Secret.of(PASSWORD))
            log("$MAC_DEFAULT: key derived in ${took.toString(DurationUnit.SECONDS, 2)} (run ${run + 1})")
        }
    }

    /** This phone's measurement for [seconds], then a database made with it in the folder and its opening timed. */
    fun measure(folder: File, seconds: Double) {
        val encryption = DatabaseEncryption.recommended(seconds = seconds)
        val started = System.nanoTime()
        val measured = EncryptionMeasurement.measure(encryption)
        log("measured ${encryption.memoryMiB} MiB, ${encryption.lanes} lanes for $seconds s: ${measured.units} iterations, " +
            "said ${"%.2f".format(measured.seconds)} s; measuring took ${elapsed(started)}")
        val made = File(folder, "Probe ${LocalTime.now().format(STAMP)} ${seconds}s.kdbx")
        val password = Secret.of(PASSWORD)
        val creating = System.nanoTime()
        val key = DatabaseCreator(Instant::now).create(newFileAt(made), "Probe $seconds s", password, null, measured)
        key.wipe()
        password.wipe()
        log("made ${made.name} in ${elapsed(creating)} (derived once, read back), ${made.length()} bytes")
        val bytes = made.readBytes()
        repeat(3) { run ->
            val took = EncryptionMeasurement.keyDerivationTime(bytes, Secret.of(PASSWORD))
            log("${made.name}: key derived in ${took.toString(DurationUnit.SECONDS, 2)} (run ${run + 1})")
        }
    }

    private fun attempt(what: String, action: () -> Unit) {
        try {
            action()
            log("$what: ok")
        } catch (failure: ErrnoException) {
            log("$what: errno ${OsConstants.errnoName(failure.errno)} (${failure.message})")
        } catch (failure: FileAlreadyExistsException) {
            log("$what: FileAlreadyExistsException")
        } catch (failure: Exception) {
            log("$what: ${failure.javaClass.simpleName}: ${failure.message}")
        }
    }

    private fun elapsed(started: Long) = (System.nanoTime() - started).nanoseconds.toString(DurationUnit.SECONDS, 2)

    private companion object {
        const val MAC_DEFAULT = "created-aes-argon2d.kdbx"
        const val PASSWORD = "pw"
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmmss")
    }
}
