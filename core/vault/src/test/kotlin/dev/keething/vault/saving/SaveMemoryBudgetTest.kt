package dev.keething.vault.saving

import dev.keething.domain.secret.Secret
import dev.keething.domain.vault.EntryChanges
import dev.keething.domain.vault.VaultEdit
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.vault.opening.OpenResult
import dev.keething.vault.opening.VaultOpener
import dev.keething.vault.probe.DiscardedBackups
import dev.keething.vault.probe.FileStore
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertIs
import org.junit.jupiter.api.Tag

/**
 * A save's memory, kept (keething-spec app.large-database AC2): a 40 MiB database (`task large:database`'s, Argon2 of
 * 1 MiB) unlocked as a session holds it and saved twice within a 224 MiB heap, about five and a half times the file. Before
 * LD1 the save held ten to twelve copies of the file and needed 434 MiB; it needs about 200 now, the file read and the
 * backup streamed; one whole copy more is about 40 MiB. Its own JVM (core:vault `memoryBudgetTest`, run by `test`), not
 * the usual test JVM, whose heap other tests share.
 */
@Tag("memory-budget")
class SaveMemoryBudgetTest {
    @Test
    fun aFortyMiBDatabaseIsUnlockedAndSavedTwiceWithinFiveAndAHalfTimesItsSize() {
        val copy = Files.createTempDirectory("budget").resolve("large.kdbx").toFile()
        File(System.getProperty("keething.largeDatabase")).copyTo(copy)
        try {
            val store = FileStore(copy)
            var opened = assertIs<OpenResult.Opened>(VaultOpener().open(store, key())).opened
            val saver = VaultSaver(store, DiscardedBackups)
            repeat(2) { round ->
                val entry = opened.vault.entries[round]
                val edit = VaultEdit.Update(entry.id, entry.lastModified, EntryChanges(notes = entry.notes + "\n(budget)"))
                val saved = assertIs<SaveResult.Saved>(saver.save(edit, key(), Instant.now()), "save ${round + 1}")
                opened.vault.wipe()
                opened.attachments.wipe()
                opened = saved.opened
            }
            opened.attachments.wipe()
        } finally {
            copy.parentFile.deleteRecursively()
        }
    }

    private fun key() = DatabaseKey.of(Secret.of("pw"))
}
