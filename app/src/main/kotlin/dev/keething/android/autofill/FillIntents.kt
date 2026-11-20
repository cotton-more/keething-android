package dev.keething.android.autofill

import java.util.UUID

/**
 * The ids of the fill screen's intents handed out with a fill response (SPEC §7, Trust). The app being filled starts
 * an intent, and could keep it and start it again later: the screen answers without being seen once per id, and shows
 * the picker or the lock screen for a used or unknown one (after a restart too). Only the latest [kept] ids are kept.
 */
internal class FillIntents(private val kept: Int = 500) {
    private val issued = LinkedHashSet<String>()

    fun issue(): String = synchronized(this) {
        UUID.randomUUID().toString().also { id ->
            issued += id
            if (issued.size > kept) issued.remove(issued.first())
        }
    }

    /** True the first time for an id this process handed out; false for a used, unknown or missing one. */
    fun answersAtOnce(id: String?): Boolean = synchronized(this) { id != null && issued.remove(id) }
}
