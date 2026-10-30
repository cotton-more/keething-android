package dev.keething.vault.databases

/**
 * An entry of one of the open databases (plan AD9; the Mac's `EntryRef`). The same UUID can be in two databases (a
 * copy of a file keeps every one), so the screens tell entries apart by their database too (keething-spec
 * databases.one-list AC1).
 */
public data class EntryRef(val database: DatabaseId, val entry: String) {
    /**
     * Its id on the screens (a row, a card) and in the settings (Recent, the declined type offers): `<database>:<entry>`
     * — `:` is in neither a KeePass UUID (base64) nor a database id (a UUID). Without a database id, the entry's own id,
     * as the ids stored before several databases were.
     */
    val text: String get() = if (database.value.isEmpty()) entry else "${database.value}:$entry"

    override fun toString(): String = text

    public companion object {
        /** Read back from [text]: an id without `:` is an entry's own id (stored before several databases). */
        public fun of(text: String): EntryRef {
            val colon = text.indexOf(':')
            return if (colon < 0) EntryRef(DatabaseId(""), text) else EntryRef(DatabaseId(text.substring(0, colon)), text.substring(colon + 1))
        }
    }
}

/**
 * What the stored ids of entries (Recent, the declined type offers) become when the open databases change (plan AD9;
 * the Mac's `StoredEntryIDs`): an open database's ref of an entry it no longer has is dropped; a database not open keeps
 * its refs, so its entries come back when it opens — unless it is no longer in the list; an entry's own id stored before
 * several databases becomes the ref of the first open database holding it, else it is dropped (no migration step).
 */
public class StoredEntryIds(
    open: List<DatabaseId>,
    /** The entries of the open databases (not their recycle bins'). */
    refs: List<EntryRef>,
    /** The list's databases; null: not known (a database not open keeps its refs). */
    private val listed: Set<DatabaseId>? = null,
) {
    private val open = open.toSet()
    private val existing = refs.mapTo(HashSet()) { it.text }
    private val ownIds = HashMap<String, String>().apply { for (ref in refs) putIfAbsent(ref.entry, ref.text) }

    /** What [stored] becomes; null: it is dropped. */
    public fun kept(stored: String): String? {
        if (stored in existing) return stored
        val ref = EntryRef.of(stored)
        if (ref.database.value.isEmpty()) return ownIds[stored]
        val gone = ref.database in open || listed?.let { ref.database !in it } == true
        return if (gone) null else stored
    }

    /** [stored] as kept, in its order, each once. */
    public fun keep(stored: List<String>): List<String> = stored.mapNotNull(::kept).distinct()
}
