package dev.keething.vault.probe

import dev.keething.domain.secret.Secret
import dev.keething.domain.vault.EntryChanges
import dev.keething.domain.vault.VaultEdit
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.vault.opening.OpenResult
import dev.keething.vault.opening.OpenedVault
import dev.keething.vault.opening.VaultOpener
import dev.keething.vault.saving.SaveResult
import dev.keething.vault.saving.VaultSaver
import java.io.File
import java.time.Instant
import kotlin.system.exitProcess
import kotlin.time.TimeSource

/**
 * The memory and time of an unlock and of saves on a COPY of a large database (keething-spec app.large-database,
 * plans/android-large-database.md LD1), as a session holds them: the database stays open while it is saved, and an
 * optional second one stays open beside it. Run in a JVM with the heap limited (`task large:save`) to find what a save
 * needs; the phone's own numbers come from its timing lines. Prints times and counts only.
 *
 * Usage: `task large:save -- <heap, e.g. 400m> <copy.kdbx> [beside.kdbx] [saves]` (password `pw`, the generator's).
 */
fun main(arguments: Array<String>) {
    if (arguments.isEmpty() || arguments.size > 3) fail("usage: SaveMemoryProbe <copy.kdbx> [beside.kdbx] [saves]")
    val copy = File(arguments[0])
    val beside = arguments.getOrNull(1)?.takeIf { it != "-" }?.let(::File)
    val saves = arguments.getOrNull(2)?.toInt() ?: 2
    val heap = Runtime.getRuntime().maxMemory() shr 20

    val held = beside?.let { open(it, "beside") }
    var opened = open(copy, "copy")
    val store = FileStore(copy)
    val saver = VaultSaver(store, DiscardedBackups)
    repeat(saves) { index ->
        val entry = opened.vault.entries[index % opened.vault.entries.size]
        val edit = VaultEdit.Update(entry.id, entry.lastModified, EntryChanges(notes = entry.notes + "\n(save ${index + 1})"))
        val started = TimeSource.Monotonic.markNow()
        when (val result = saver.save(edit, DatabaseKey.of(Secret.of("pw")), Instant.now())) {
            is SaveResult.Saved -> {
                println("Save ${index + 1}: ${started.elapsedNow()} within a ${heap} MiB heap")
                opened.vault.wipe()
                opened.attachments.wipe()
                opened = result.opened
            }
            is SaveResult.Failed -> {
                println("RESULT: save ${index + 1} failed within a ${heap} MiB heap after ${started.elapsedNow()}: ${result.failure}")
                exitProcess(1)
            }
        }
    }
    held?.attachments?.wipe()
    println("RESULT: $saves saves within a $heap MiB heap")
}

private fun open(file: File, what: String): OpenedVault {
    val started = TimeSource.Monotonic.markNow()
    return when (val result = VaultOpener().open(FileStore(file), DatabaseKey.of(Secret.of("pw")))) {
        is OpenResult.Opened -> result.opened.also {
            println("Unlock ($what, ${file.length() shr 20} MiB): ${started.elapsedNow()}, ${it.vault.entries.size} entries")
        }
        is OpenResult.Failed -> {
            println("RESULT: $what does not open within a ${Runtime.getRuntime().maxMemory() shr 20} MiB heap: ${result.failure}")
            exitProcess(1)
        }
    }
}
