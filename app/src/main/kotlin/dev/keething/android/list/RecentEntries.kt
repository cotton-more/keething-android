package dev.keething.android.list

import dev.keething.domain.settings.SettingsStore
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.EntryRef
import dev.keething.vault.databases.StoredEntryIds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The entries used last, newest first: the list shows them first when nothing is typed, across the open databases (Mac
 * `RecentEntries`). Only the entries' refs are kept ([EntryRef.text]), so they survive a lock and a restart and say
 * nothing without the database. An entry is used when its card is opened or something is copied from it; a swipe
 * removes it (SPEC §6.2). 8 are kept per database, so a locked database's entries are not pushed out by those used
 * meanwhile in the open ones (keething-spec search.recent AC7).
 */
internal class RecentEntries(private val store: SettingsStore) {
    private val mutableIds = MutableStateFlow(store.string(KEY).orEmpty().split('\n').filter { it.isNotEmpty() })
    val ids: StateFlow<List<String>> = mutableIds.asStateFlow()

    fun note(id: String) {
        val ids = ids.value
        if (ids.firstOrNull() == id) return
        save(trimmed(listOf(id) + ids.filter { it != id }))
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
                save(trimmed(ids.take(at) + id + ids.drop(at)))
            }
        }
    }

    /** Entries gone from their open database are dropped; a database not open keeps its own ([StoredEntryIds]). */
    fun keep(stored: StoredEntryIds) {
        val kept = stored.keep(ids.value)
        if (kept != ids.value) save(kept)
    }

    private fun save(ids: List<String>) {
        mutableIds.value = ids
        store.setString(KEY, ids.joinToString("\n"))
    }

    companion object {
        /** How many are listed, in all; and kept, per database. */
        const val LIMIT = 8
        private const val KEY = "recentEntries" // not user text

        /** The newest [LIMIT] of each database. */
        private fun trimmed(ids: List<String>): List<String> {
            val counts = HashMap<DatabaseId, Int>()
            return ids.filter { id -> counts.merge(EntryRef.of(id).database, 1, Int::plus)!! <= LIMIT }
        }
    }
}
