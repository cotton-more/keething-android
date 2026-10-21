package dev.keepassbar.android.autofill

import android.os.SystemClock
import dev.keepassbar.autofill.target.FillTarget
import dev.keepassbar.domain.secret.Secret
import java.util.UUID

/** A login typed into another app or page, on its way to the save screen (SPEC §7, Save). */
internal class PendingSave(
    val target: FillTarget,
    val username: String,
    val password: Secret,
    /** A password change's current password: tells which entry changes when no username was typed. */
    val currentPassword: Secret?,
) {
    fun wipe() {
        password.wipe()
        currentPassword?.wipe()
    }
}

/**
 * The logins between the save request and the save screen. They stay in this process: the screen's intent
 * carries only a random token, so the password never passes through the system in an intent. One that is
 * not taken within [LIFETIME_MS] is wiped.
 */
internal class PendingSaves(private val now: () -> Long = SystemClock::elapsedRealtime) {
    private val saves = mutableMapOf<String, Pair<Long, PendingSave>>()

    fun put(save: PendingSave): String = synchronized(this) {
        expire()
        UUID.randomUUID().toString().also { saves[it] = now() to save }
    }

    /** The login for [token], once; null when it was taken, has expired or the process started anew. */
    fun take(token: String?): PendingSave? = synchronized(this) {
        expire()
        token?.let { saves.remove(it)?.second }
    }

    private fun expire() {
        val old = saves.filterValues { (at, _) -> now() - at > LIFETIME_MS }
        old.forEach { (token, value) -> value.second.wipe(); saves.remove(token) }
    }

    private companion object {
        const val LIFETIME_MS = 10 * 60 * 1000L
    }
}
