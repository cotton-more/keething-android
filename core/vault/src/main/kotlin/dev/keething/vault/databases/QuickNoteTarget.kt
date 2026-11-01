package dev.keething.vault.databases

/** The database a quick note goes to (SPEC §6.4, keething-spec app.quick-note AC1; Mac `QuickNoteTarget`). */
public object QuickNoteTarget {
    /**
     * The one set in Settings while it is in the list, else the current one (the one the lock screen shows), else the
     * list's first; null with no database at all.
     */
    public fun resolve(setting: DatabaseId?, databases: Databases): DatabaseId? =
        setting?.takeIf { databases.record(it) != null } ?: databases.current?.id ?: databases.records.firstOrNull()?.id
}
