package dev.keething.android.databases

import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.keething.android.storage.AllFilesAccess
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
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * The window's databases as the vault's screens show them (keething-spec databases.not-open, plan AD12): those not open
 * with their states, and ⋮ › Databases' sections. The files are looked at as they are now: whenever the list, a
 * session or a fingerprint changes, and on [refresh] (a screen coming back: a card put in, a key file moved back).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class WindowDatabasesModel(list: DatabaseList, window: StateFlow<List<WindowDatabase>>) : ViewModel() {
    private val refreshes = MutableStateFlow(0)
    private val files = DatabaseFiles.live(AllFilesAccess::isGranted)
    private val root = Environment.getExternalStorageDirectory().path

    val overview: StateFlow<DatabasesOverview?> = combine(list.state, window, refreshes) { databases, states, _ -> databases to states }
        .mapLatest { (databases, states) ->
            withContext(Dispatchers.IO) {
                val lines = databases.lines(files, root, Instant.now(), ZoneId.systemDefault())
                // The chooser's run is plan AD13's: nothing is skipped yet.
                val notOpen = NotOpenDatabases.of(states, lines, skipped = emptySet())
                DatabasesOverview.of(
                    lines = lines,
                    window = databases.window.map { it.id },
                    open = states.filter { it.session is SessionState.Unlocked }.map { it.id },
                    notOpen = notOpen,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun refresh() {
        refreshes.value++
    }
}
