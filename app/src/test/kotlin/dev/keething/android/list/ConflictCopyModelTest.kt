package dev.keething.android.list

import dev.keething.android.list.ConflictCopyModel.Other
import dev.keething.android.ui.Notice
import dev.keething.android.ui.UiText
import dev.keething.android.ui.english
import dev.keething.android.ui.uiPlain
import dev.keething.domain.storage.ConflictCopy
import dev.keething.vault.opening.OpenFailure
import dev.keething.vault.saving.SaveFailure
import dev.keething.vault.session.CopyContents
import dev.keething.vault.session.SaveOutcome
import dev.keething.vault.session.VaultSummary
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** Resolve…'s sheet (keething-spec kdbx.conflict-copies AC6). */
class ConflictCopyModelTest {
    private val copy = ConflictCopy("Personal.sync-conflict-20261108-101500-ABCDEF2.kdbx", LocalDateTime.of(2026, 11, 8, 10, 15), "ABCDEF2")
    private val summary = VaultSummary(12, Instant.parse("2026-11-08T10:15:00Z"))

    private class Fixture(
        scope: TestScope,
        contents: CopyContents? = CopyContents.Read(VaultSummary(13, null)),
        replaceRefused: UiText? = null,
        copy: ConflictCopy,
        summary: VaultSummary,
    ) {
        val taken = CompletableDeferred<SaveOutcome>()
        val removed = CompletableDeferred<SaveFailure?>()
        var takes = 0
        var removes = 0
        val done = mutableListOf<Notice>()
        val model = ConflictCopyModel(
            file = File("/storage/emulated/0/Sync/Personal.kdbx"),
            copy = copy,
            open = summary,
            replaceRefused = replaceRefused,
            contents = { contents },
            take = { takes++; taken },
            remove = { removes++; removed },
            scope = scope.backgroundScope,
            onDone = { done += it },
        )
    }

    private fun TestScope.fixture(contents: CopyContents? = CopyContents.Read(VaultSummary(13, null)), replaceRefused: UiText? = null) =
        Fixture(this, contents, replaceRefused, copy, summary).also { runCurrent() }

    // spec: kdbx.conflict-copies/AC6
    @Test
    fun theOtherVersionIsReadAndUsingItIsSaidWhenDone() = runTest {
        val f = fixture()
        assertEquals(Other.Read(VaultSummary(13, null)), f.model.other)
        assertTrue(f.model.canUseOther)

        f.model.useOther()
        f.model.keepOpen() // nothing else while it runs
        assertEquals(ConflictCopyModel.Choice.USE_OTHER, f.model.working)
        assertEquals(0, f.removes)
        f.taken.complete(SaveOutcome.Saved())
        runCurrent()

        assertEquals(1, f.takes)
        assertEquals(listOf("Personal.kdbx is the other version now (the one before is in Keething's backups)."), f.done.map { it.text.english() })
    }

    @Test
    fun aCopyThatDoesNotOpenCanOnlyBeDeleted() = runTest {
        val f = fixture(CopyContents.NotOpened(OpenFailure.WrongPassword))

        assertEquals("It does not open with this database's password or key file (changed on another device?).", (f.model.other as Other.NotOpened).why.english())
        assertFalse(f.model.canUseOther)
        f.model.useOther()
        assertEquals(0, f.takes)

        f.model.keepOpen()
        f.removed.complete(null)
        runCurrent()
        assertEquals(listOf("Kept Personal.kdbx. The other version was deleted (a copy is in Keething's backups)."), f.done.map { it.text.english() })
    }

    @Test
    fun aReadOnlyDatabaseDoesNotTakeTheCopy() = runTest {
        val f = fixture(replaceRefused = uiPlain("read only"))

        assertFalse(f.model.canUseOther)
        f.model.useOther()
        assertEquals(0, f.takes)
    }

    @Test
    fun aFailureIsSaidInTheSheetWhichStays() = runTest {
        val f = fixture()

        f.model.useOther()
        f.taken.complete(SaveOutcome.Failed(SaveFailure.CopyGone))
        runCurrent()

        assertEquals("The other version is not there any more (deleted or synced away). Nothing was changed.", f.model.problem?.english())
        assertNull(f.model.working)
        assertEquals(emptyList(), f.done)
    }

    @Test
    fun aCopyTakenButNotDeletedIsSaidAsAProblem() = runTest {
        val f = fixture()

        f.model.useOther()
        f.taken.complete(SaveOutcome.Saved(copyLeft = copy.fileName))
        runCurrent()

        assertTrue(f.done.single().isProblem)
    }
}
