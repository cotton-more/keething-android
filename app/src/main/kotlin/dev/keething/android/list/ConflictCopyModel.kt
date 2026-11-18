package dev.keething.android.list

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.keething.android.editor.describe
import dev.keething.android.editor.problem
import dev.keething.android.R
import dev.keething.android.ui.Notice
import dev.keething.android.ui.UiText
import dev.keething.android.ui.afterColon
import dev.keething.android.ui.uiText
import dev.keething.android.unlock.describe
import dev.keething.domain.storage.ConflictCopy
import dev.keething.vault.saving.SaveFailure
import dev.keething.vault.session.CopyContents
import dev.keething.vault.session.SaveOutcome
import dev.keething.vault.session.VaultSummary
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch

/**
 * Resolve… on a conflict copy's banner (keething-spec kdbx.conflict-copies AC6): the open database and the other version
 * side by side, each in brief, and the two choices, each saying what it does before it is done. Keep the open one deletes
 * the copy (kept among the database's backups first); Use the other one makes the copy the database (the database backed
 * up first), only when it opens with the database's key. Nothing is merged. Call it on the main thread.
 */
internal class ConflictCopyModel(
    /** The database's file, as the screens name it. */
    val file: File,
    val copy: ConflictCopy,
    /** The open database in brief. */
    val open: VaultSummary,
    /** Why the copy cannot take the database's place here (read-only), or null. */
    val replaceRefused: UiText?,
    private val contents: suspend () -> CopyContents?,
    private val take: () -> Deferred<SaveOutcome>?,
    private val remove: () -> Deferred<SaveFailure?>?,
    private val scope: CoroutineScope,
    /** Done: the sheet closes, and this is said. */
    private val onDone: (Notice) -> Unit,
) {
    sealed interface Other {
        data object Reading : Other

        data class Read(val summary: VaultSummary) : Other

        /** It does not open with the database's key, or at all: [why]. It can only be deleted here. */
        data class NotOpened(val why: UiText) : Other
    }

    enum class Choice { KEEP_OPEN, USE_OTHER }

    var other: Other by mutableStateOf(Other.Reading)
        private set

    /** The choice being carried out; nothing else can be chosen meanwhile. */
    var working: Choice? by mutableStateOf(null)
        private set

    /** Why the last choice did nothing, said in the sheet. */
    var problem: UiText? by mutableStateOf(null)
        private set

    /** Use the other one is offered: the copy opened, the database can be changed, nothing running. */
    val canUseOther: Boolean get() = other is Other.Read && replaceRefused == null && working == null

    init {
        scope.launch {
            other = when (val read = contents()) {
                is CopyContents.Read -> Other.Read(read.summary)
                is CopyContents.NotOpened -> Other.NotOpened(
                    if (read.failure.isWrongKey) {
                        uiText(R.string.list_conflict_wrong_key)
                    } else {
                        read.failure.describe(copy.fileName)
                    },
                )
                null -> Other.NotOpened(uiText(R.string.list_conflict_locked))
            }
        }
    }

    fun keepOpen() {
        if (working != null) return
        val removing = remove() ?: return onDone(Notice(LOCKED, isProblem = true))
        working = Choice.KEEP_OPEN
        problem = null
        scope.launch {
            val failure = removing.await()
            working = null
            if (failure == null) {
                onDone(Notice(uiText(R.string.list_conflict_kept, file.name)))
            } else {
                problem = uiText(R.string.list_conflict_still_there, failure.describe(file).afterColon())
            }
        }
    }

    fun useOther() {
        if (!canUseOther) return
        val taking = take() ?: return onDone(Notice(LOCKED, isProblem = true))
        working = Choice.USE_OTHER
        problem = null
        scope.launch {
            val outcome = taking.await()
            working = null
            when {
                outcome is SaveOutcome.Saved && outcome.copyLeft != null -> onDone(
                    Notice(uiText(R.string.list_conflict_used_copy_left, file.name), isProblem = true),
                )
                outcome is SaveOutcome.Saved -> onDone(Notice(uiText(R.string.list_conflict_used, file.name)))
                outcome == SaveOutcome.Interrupted -> onDone(Notice(outcome.problem(file)!!, isProblem = true))
                else -> problem = outcome.problem(file)
            }
        }
    }

    private companion object {
        val LOCKED = uiText(R.string.list_conflict_locked_nothing_changed)
    }
}
