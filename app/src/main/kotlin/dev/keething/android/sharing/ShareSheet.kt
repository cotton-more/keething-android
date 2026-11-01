package dev.keething.android.sharing

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import dev.keething.android.quicknote.QuickNoteActivity

/**
 * The system's share sheet for what Keething sends (SPEC §6.2, §10.3): a code's pictures or text, a password, an
 * address, a bank account's details. It leaves out Keething's own targets, "Read with Keething" and Keething note:
 * Keething does not share with itself.
 */
internal object ShareSheet {
    /** Keething's own share targets: every activity of the app that takes a share. */
    val OWN_TARGETS: List<String> = listOf(ReceiveCodeActivity::class.java.name, QuickNoteActivity::class.java.name)

    /** The sheet for [send]; [chosen] hears the app chosen, [leaveOut] are other apps not offered. */
    fun of(context: Context, send: Intent, chosen: IntentSender? = null, leaveOut: List<ComponentName> = emptyList()): Intent {
        val own = OWN_TARGETS.map { ComponentName(context, it) }
        return Intent.createChooser(send, null, chosen).putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, (own + leaveOut).toTypedArray())
    }
}
