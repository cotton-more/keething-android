package dev.keething.vault.session

import dev.keething.domain.storage.ConflictCopy
import dev.keething.domain.storage.DatabaseChangedException
import dev.keething.domain.storage.DatabaseRead
import dev.keething.domain.storage.DatabaseStore
import dev.keething.domain.storage.FileVersion
import dev.keething.domain.storage.StoppedSave
import dev.keething.domain.storage.KeyFileRead
import dev.keething.domain.storage.KeyFileSource
import dev.keething.domain.time.Clock
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.test.TestCoroutineScheduler

internal fun fixtureBytes(name: String): ByteArray = File(System.getProperty("keething.fixtures"), "$name.kdbx").readBytes()

/**
 * A database file in memory: [change] replaces it (null: gone) and tells the watchers, as Syncthing would; so does
 * the app's own [replace], which fails once when [failNextReplace] is set.
 */
internal class FakeStore(private var bytes: ByteArray?) : DatabaseStore {
    override val fileName: String = "test.kdbx"
    var reads = 0
        private set
    var replaced = 0
        private set
    var failNextReplace = false

    /** Runs before each read with its number (1 for the first): another device changing the file meanwhile. */
    var beforeRead: (Int) -> Unit = {}

    val content: ByteArray? get() = bytes?.copyOf()
    var copies: List<ConflictCopy> = emptyList()
    private var changes = 0L
    private var current = versionOf(bytes)
    private val watchers = mutableListOf<() -> Unit>()
    val watching: Int get() = watchers.size

    /** A save the app did not finish, put right: said by every read until [stoppedSaveSaid]. */
    var stoppedSave: StoppedSave? = null
    var stoppedSavesSaid = 0
        private set

    override fun read(): DatabaseRead {
        reads++
        beforeRead(reads)
        val stored = bytes ?: return DatabaseRead.Missing
        return DatabaseRead.Found(stored.copyOf(), current!!, stoppedSave)
    }

    override fun stoppedSaveSaid() {
        stoppedSave = null
        stoppedSavesSaid++
    }

    override fun version(): FileVersion? = current

    override fun replace(bytes: ByteArray, expected: FileVersion?) {
        if (failNextReplace) {
            failNextReplace = false
            throw IOException("disk full")
        }
        if (expected != null && expected != current) throw DatabaseChangedException("changed since $expected")
        replaced++
        change(bytes.copyOf())
    }

    override fun conflictCopies(): List<ConflictCopy> = copies

    override fun watch(onChange: () -> Unit): AutoCloseable {
        watchers += onChange
        return AutoCloseable { watchers -= onChange }
    }

    fun change(newBytes: ByteArray?, notify: Boolean = true) {
        bytes = newBytes
        changes++
        current = versionOf(newBytes)
        if (notify) watchers.toList().forEach { it() }
    }

    fun notifyWatchers() = watchers.toList().forEach { it() }

    private fun versionOf(content: ByteArray?) =
        content?.let { FileVersion(it.size.toLong(), Instant.ofEpochSecond(changes), "inode-$changes") }
}

/** Time from the test's virtual clock; [sleep] moves the monotonic time on without running timers (deep sleep). */
internal class FakeClock(private val scheduler: TestCoroutineScheduler) : Clock {
    private var slept = Duration.ZERO

    override fun now(): Instant = Instant.parse("2026-10-18T12:00:00Z").plusMillis(elapsed().inWholeMilliseconds)

    override fun elapsed(): Duration = scheduler.currentTime.milliseconds + slept

    fun sleep(duration: Duration) {
        slept += duration
    }
}

internal fun keyFileBytes(name: String): ByteArray = File(System.getProperty("keething.fixtures"), "keyfiles/$name").readBytes()

/** A key file in memory ([content] null: not there); counts its reads and keeps what each handed out. */
internal open class FakeKeyFile(var content: ByteArray?, override val fileName: String = "work.keyx") : KeyFileSource {
    var denied = false
    val handedOut = mutableListOf<ByteArray>()
    val reads: Int get() = handedOut.size + misses
    private var misses = 0

    override fun read(): KeyFileRead {
        if (denied) return KeyFileRead.AccessDenied.also { misses++ }
        val bytes = content?.copyOf() ?: return KeyFileRead.Missing.also { misses++ }
        handedOut += bytes
        return KeyFileRead.Found(bytes)
    }
}
