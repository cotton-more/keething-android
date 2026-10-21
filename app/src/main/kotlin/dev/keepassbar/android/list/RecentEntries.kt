package dev.keepassbar.android.list

import dev.keepassbar.domain.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The entries used last, newest first: the list shows them first when nothing is typed (Mac
 * `RecentEntries`). Only ids are kept, so they survive a lock and a restart and say nothing without the
 * database. An entry is used when its card is opened or something is copied from it; a swipe removes it
 * (SPEC §6.2).
 */
internal class RecentEntries(private val store: SettingsStore) {
    private val mutableIds = MutableStateFlow(store.string(KEY).orEmpty().split('\n').filter { it.isNotEmpty() })
    val ids: StateFlow<List<String>> = mutableIds.asStateFlow()

    fun note(id: String) {
        val ids = ids.value
        if (ids.firstOrNull() == id) return
        save((listOf(id) + ids.filter { it != id }).take(LIMIT))
    }

    /**
     * Takes [id] off the list; the result puts it back in its place (before the entries used earlier than
     * it), unless it was used again meanwhile: then it is at the front already. Null when it was not on the list.
     */
    fun remove(id: String): (() -> Unit)? {
        val before = ids.value
        val index = before.indexOf(id)
        if (index < 0) return null
        save(before.filter { it != id })
        val older = before.drop(index + 1).toSet()
        return {
            val ids = ids.value
            if (id !in ids) {
                val at = ids.indexOfFirst(older::contains).takeIf { it >= 0 } ?: ids.size
                save((ids.take(at) + id + ids.drop(at)).take(LIMIT))
            }
        }
    }

    /** Entries gone from the vault (deleted, or another database) are dropped. */
    fun keepOnly(existing: Set<String>) {
        val kept = ids.value.filter(existing::contains)
        if (kept != ids.value) save(kept)
    }

    private fun save(ids: List<String>) {
        mutableIds.value = ids
        store.setString(KEY, ids.joinToString("\n"))
    }

    private companion object {
        const val KEY = "recentEntries"
        const val LIMIT = 8
    }
}
