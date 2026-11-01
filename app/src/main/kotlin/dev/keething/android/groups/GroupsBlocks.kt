package dev.keething.android.groups

import dev.keething.android.databases.DatabaseMark
import dev.keething.domain.vault.Vault
import dev.keething.search.EntrySearch
import dev.keething.search.FilterCompletion
import dev.keething.search.FilterKind
import dev.keething.vault.databases.DatabaseId
import java.text.Collator

/**
 * One open database's block on Groups & tags (keething-spec plans/android-groups.md G7): its header (the mark and how
 * many entries it has) over its groups, recycle bin and tags. [several]: two or more are open, so a row's search keeps
 * this database's entries only.
 */
internal class GroupsBlock(val mark: DatabaseMark, val overview: GroupsOverview, val entries: Int, private val several: Boolean) {
    val id: DatabaseId get() = mark.id

    /**
     * The list's search for a row's [rowSearch] ([GroupsOverview.GroupRow.search], [GroupsOverview.TagRow.search],
     * [GroupsOverview.RECYCLE_BIN_SEARCH]): as it is with one database open; with two or more the list's `!` filter goes
     * with it, after the `-` that makes a search the recycle bin's.
     */
    fun search(rowSearch: String): String {
        if (!several) return rowSearch
        val database = FilterCompletion.token(FilterKind.DATABASE, mark.label.full) + " "
        return EntrySearch.recycleBinQuery(rowSearch)?.let { "- $database${it.trimStart()}" } ?: (database + rowSearch)
    }
}

/**
 * The blocks of Groups & tags over the open databases, in the window's order. A database read again (a save, a sync)
 * is built again, the others are kept as they were; one closed or locked alone goes with its block.
 */
internal class GroupsBlocks(private val collator: Collator = Collator.getInstance()) {
    private var built: Map<DatabaseId, Pair<Vault, GroupsOverview>> = emptyMap()

    /** [open]: each open database's mark and the vault it read last. */
    fun of(open: List<Pair<DatabaseMark, Vault>>): List<GroupsBlock> {
        val overviews = open.associate { (mark, vault) ->
            val kept = built[mark.id]?.takeIf { it.first === vault }
            mark.id to (kept ?: (vault to GroupsOverview.of(vault, collator)))
        }
        built = overviews
        return open.map { (mark, vault) -> GroupsBlock(mark, overviews.getValue(mark.id).second, vault.entries.size, open.size >= 2) }
    }
}
