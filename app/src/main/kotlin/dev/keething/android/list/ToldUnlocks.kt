package dev.keething.android.list

/**
 * The unlocks whose key derivation the list has told ("Unlocked · key derivation …"), kept for the whole app: the list is
 * built again each time the vault comes back from a lock screen (another database's, then Back), and a database still
 * open there was not unlocked again.
 */
internal class ToldUnlocks {
    private val told = HashSet<String>()

    /** True the first time [unlock] ([key]) is asked about. */
    fun firstTime(unlock: String): Boolean = told.add(unlock)

    companion object {
        /** An unlock: its session's number and how many times that session had locked before it. */
        fun key(session: Int, locks: Int): String = "$session/$locks"
    }
}
