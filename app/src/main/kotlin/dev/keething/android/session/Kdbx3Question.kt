package dev.keething.android.session

import dev.keething.android.ui.afterColon
import dev.keething.android.R
import dev.keething.android.editor.describe
import dev.keething.android.editor.savedNotice
import dev.keething.android.ui.Notice
import dev.keething.android.ui.uiText
import dev.keething.vault.databases.DatabaseSession
import dev.keething.vault.session.SaveOutcome
import dev.keething.vault.session.SessionState
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The question a KDBX 3.1 database asks (keething-spec kdbx.kdbx3 AC5, AC6): upgrade it to KDBX 4.0, or view it only.
 * Asked on its lock screen before the key, the version told from the file's first bytes; a database opened another way
 * (the chooser's run, a fingerprint over the list, autofill) opens viewed only and asks over the list. Once per opening:
 * every lock forgets the answer, and a change tried on it viewed only asks again. Upgrade runs as soon as it is open
 * ([dev.keething.vault.session.VaultSession.upgrade]) and says how it went. Call it on the main thread.
 */
internal class Kdbx3Question(private val scope: CoroutineScope) {
    /** Each session's answer at this opening, by its number: true to upgrade. Started upgrades count as answered. */
    private val answers = HashMap<Int, Boolean>()

    private val mutableAsking = MutableStateFlow<DatabaseSession?>(null)

    /** The database open and viewed only whose question shows over the list. */
    val asking: StateFlow<DatabaseSession?> = mutableAsking.asStateFlow()

    /** Where its outcome is said (the window's notices). */
    var tell: (Notice) -> Unit = {}

    /** Whether [database]'s file is KDBX 3.1, told from its first 12 bytes; false when it cannot be read. */
    suspend fun isKdbx3(database: DatabaseSession): Boolean = withContext(Dispatchers.IO) { database.session.isKdbx3File() }

    fun answered(database: DatabaseSession): Boolean = database.number in answers

    /** The answer, from its lock screen or over the list: an open database is upgraded at once. */
    fun answer(database: DatabaseSession, upgrade: Boolean) {
        answers[database.number] = upgrade
        if (mutableAsking.value?.number == database.number) mutableAsking.value = null
        if (upgrade && database.isViewOnly) upgrade(database)
    }

    /** A change tried on it viewed only (AC6), or opened with no answer: the question over the list. */
    fun ask(database: DatabaseSession) {
        if (database.isViewOnly) mutableAsking.value = database
    }

    /** Follows a new session of [database] in [watching], its lifetime. */
    fun follow(database: DatabaseSession, watching: CoroutineScope) {
        database.session.onViewOnlyRefusal = { ask(database) }
        watching.launch {
            database.session.state.collect { state ->
                when {
                    state is SessionState.Locked -> {
                        answers.remove(database.number)
                        if (mutableAsking.value?.number == database.number) mutableAsking.value = null
                    }
                    state is SessionState.Unlocked && state.viewOnly -> when (answers[database.number]) {
                        true -> upgrade(database)
                        null -> ask(database)
                        false -> {}
                    }
                }
            }
        }
    }

    private fun upgrade(database: DatabaseSession) {
        // Started once: the vault stays viewed only until the new file is read back, and a failed one is asked again.
        answers[database.number] = false
        val outcome = database.session.upgrade() ?: return
        scope.launch {
            val file = File(database.location.fileName)
            tell(
                when (val ended = outcome.await()) {
                    is SaveOutcome.Saved -> savedNotice(uiText(R.string.session_kdbx3_upgraded, file.name, ended.keptCopy.toString()), ended.mayNotSync)
                    is SaveOutcome.Failed -> Notice(uiText(R.string.editor_kdbx3_not_upgraded, ended.failure.describe(file).afterColon()), isProblem = true)
                    SaveOutcome.Interrupted -> Notice(uiText(R.string.session_kdbx3_interrupted), isProblem = true)
                },
            )
        }
    }
}

/** Whether its database is open and viewed only (KDBX 3.1; keething-spec kdbx.kdbx3 AC6). */
internal val DatabaseSession.isViewOnly: Boolean get() = (session.state.value as? SessionState.Unlocked)?.viewOnly == true

/** Whether its database is open and read-only: viewed only, or too large to save (keething-spec app.large-database AC3). */
internal val DatabaseSession.isReadOnly: Boolean get() = (session.state.value as? SessionState.Unlocked)?.readOnly == true
