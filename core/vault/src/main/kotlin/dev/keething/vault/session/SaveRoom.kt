package dev.keething.vault.session

import dev.keething.vault.opening.DatabaseSize

/**
 * Whether the app has the memory to save a database (keething-spec app.large-database AC3): judged when it unlocks,
 * against the databases open then ([SaveMemory.judge]), and kept until it locks, so another database opened later
 * never changes it (the one opened later is judged with this one's save in mind).
 */
public sealed interface SaveRoom {
    /** A save fits with room to spare. */
    public data object Enough : SaveRoom

    /** A save fits, near the limit: the list says so once ([VaultSession.nearLimitSaid]). */
    public data object Near : SaveRoom

    /**
     * A save would not fit safely: the database is read-only until it locks, every change refused
     * ([dev.keething.vault.saving.SaveFailure.TooLarge]). [alone]: so even with no other database open; otherwise the
     * databases open beside it are what tips it (locking them and unlocking it again makes it changeable).
     */
    public data class TooLarge(val alone: Boolean) : SaveRoom
}

/** An open database as [SaveMemory.judge] counts it: its [size], and whether it may still be saved ([saves]). */
public data class OpenSize(val size: DatabaseSize, val saves: Boolean)

/**
 * The memory a save needs against what the app is given (keething-spec app.large-database AC3,
 * plans/android-large-database.md §1, LD2). Measured on the Pixel 6 after LD1 (a release build, its 512 MiB heap):
 * open and idle, the app holds about [BASE] and [HELD_PER_BYTE] times every open database's file (107 MiB with the
 * 82.7 MiB file alone, 158–192 MiB with the 41.4 MiB one beside it or a 100 MiB one); a save needs about [SAVE_PER_BYTE]
 * times its own file over that (366 MiB at the 82.7 MiB file's save alone, 460 MiB beside the 41.4 MiB one, 506 MiB at
 * the 100 MiB one's beside it). Entries do not count, bytes do. The estimate is above each of those (413, 467 and
 * 550 MiB), on purpose.
 */
public object SaveMemory {
    public const val BASE: Long = 16L shl 20
    public const val HELD_PER_BYTE: Double = 1.3
    public const val SAVE_PER_BYTE: Double = 3.5

    /** From this share of the heap a save is near the limit: said once, still changeable. */
    public const val NEAR: Double = 0.75

    /** From this share a save is not safe: read-only (the owner, 2026-11-07: "calmer so"). */
    public const val SAFE: Double = 0.90

    /** What the app holds with [open] databases open, and what a save of [saving] needs over it, in bytes. */
    public fun need(saving: DatabaseSize, open: List<DatabaseSize>): Long =
        BASE + (HELD_PER_BYTE * open.sumOf { it.fileBytes }).toLong() + (SAVE_PER_BYTE * saving.fileBytes).toLong()

    /**
     * The room for [size], about to be open beside the [open] ones, in a heap of [heap] bytes: too large when its own
     * save would pass [SAFE], or when it would leave one of the [open] ones that still saves without that room.
     */
    public fun judge(size: DatabaseSize, open: List<OpenSize>, heap: Long): SaveRoom {
        val all = open.map { it.size } + size
        val limit = SAFE * heap
        if (need(size, listOf(size)) >= limit) return SaveRoom.TooLarge(alone = true)
        if (need(size, all) >= limit || open.any { it.saves && need(it.size, all) >= limit }) return SaveRoom.TooLarge(alone = false)
        return if (need(size, all) >= NEAR * heap) SaveRoom.Near else SaveRoom.Enough
    }
}
