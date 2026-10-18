package dev.keepassbar.vault.session

import dev.keepassbar.domain.storage.ConflictCopy
import dev.keepassbar.domain.storage.DatabaseRead
import dev.keepassbar.domain.storage.DatabaseStore
import dev.keepassbar.domain.storage.FileVersion
import dev.keepassbar.domain.time.Clock
import java.io.File
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.test.TestCoroutineScheduler

internal fun fixtureBytes(name: String): ByteArray = File(System.getProperty("keepassbar.fixtures"), "$name.kdbx").readBytes()

/** A database file in memory: [change] replaces it (null: gone) and tells the watchers, as Syncthing would. */
internal class FakeStore(private var bytes: ByteArray?) : DatabaseStore {
    override val fileName: String = "test.kdbx"
    var reads = 0
        private set
    var copies: List<ConflictCopy> = emptyList()
    private var changes = 0L
    private var current = versionOf(bytes)
    private val watchers = mutableListOf<() -> Unit>()
    val watching: Int get() = watchers.size

    override fun read(): DatabaseRead {
        reads++
        val content = bytes ?: return DatabaseRead.Missing
        return DatabaseRead.Found(content.copyOf(), current!!)
    }

    override fun version(): FileVersion? = current

    override fun replace(bytes: ByteArray): Unit = error("read-only")

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
