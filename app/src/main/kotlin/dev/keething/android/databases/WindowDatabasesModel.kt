package dev.keething.android.databases

import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.DatabaseList
import dev.keething.vault.session.SessionState
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * The window's databases as the screens show them (keething-spec databases.not-open, plan AD12): those not open with
 * their states, ⋮ › Databases' sections, and the chooser's rows (plan AD13). The files are looked at as they are now:
 * whenever the list, a session, a fingerprint or the run's Skipped ([skipped]) changes, and on [refresh] (a screen coming
 * back: a card put in, a key file moved back).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class WindowDatabasesModel(
    list: DatabaseList,
    window: StateFlow<List<WindowDatabase>>,
    skipped: StateFlow<Set<DatabaseId>>,
    private val files: DatabaseFiles,
) : ViewModel() {
    private val refreshes = MutableStateFlow(0)
    private val root = Environment.getExternalStorageDirectory().path

    /** What was looked at last: ⋮ › Databases' sections and the chooser's rows. */
    private class Looked(val overview: DatabasesOverview, val chooser: List<ChooserRow>)

    private val looked: StateFlow<Looked?> = combine(list.state, window, skipped, refreshes) { databases, states, skippedIds, _ -> Triple(databases, states, skippedIds) }
        .mapLatest { (databases, states, skippedIds) ->
            withContext(Dispatchers.IO) {
                val lines = databases.lines(files.lookedAt(databases), root, Instant.now(), ZoneId.systemDefault())
                val notOpen = NotOpenDatabases.of(states, lines, skippedIds)
                val overview = DatabasesOverview.of(
                    lines = lines,
                    window = databases.window.map { it.id },
                    open = states.filter { it.session is SessionState.Unlocked }.map { it.id },
                    notOpen = notOpen,
                )
                Looked(overview, ChooserRows.of(states, lines) { id -> databases.record(id)?.checkedInChooser ?: true })
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val overview: StateFlow<DatabasesOverview?> = looked.map { it?.overview }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The chooser's rows: the window's databases, how each opens (keething-spec databases.chooser AC3, AC4). */
    val chooser: StateFlow<List<ChooserRow>?> = looked.map { it?.chooser }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun refresh() {
        refreshes.value++
    }
}
