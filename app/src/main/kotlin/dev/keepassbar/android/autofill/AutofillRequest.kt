package dev.keepassbar.android.autofill

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.view.autofill.AutofillId
import dev.keepassbar.autofill.form.AutofillForm
import dev.keepassbar.autofill.target.FillTarget
import dev.keepassbar.search.site.SiteAddress
import java.util.concurrent.atomic.AtomicInteger

/** What the autofill screen is opened for. */
internal enum class AutofillMode {
    /** The vault was locked: unlock, then fill the one matching entry or choose among them. */
    UNLOCK,

    /** "Search KeePassBar": choose any entry. */
    PICK,

    /** An entry with a one-time code: the code is made when it is chosen, not when it was offered. */
    CODE,
}

/**
 * Everything the autofill screen needs, carried in its own immutable intent (SPEC §7). The screen trusts
 * only this, never the view structure the system adds to the intent: the app being filled starts the
 * intent and could add a structure that names another app.
 */
internal class AutofillRequest(
    val mode: AutofillMode,
    val form: AutofillForm,
    /** The views of [form]'s fields. */
    val ids: Map<Int, AutofillId>,
    val entryId: String? = null,
) {
    /** For a dataset's authentication: each one its own pending intent (extras do not tell them apart). */
    fun sender(context: Context): IntentSender {
        val fields = form.fields
        val intent = Intent(context, AutofillActivity::class.java)
            .putExtra(MODE, mode.name)
            .putExtra(ENTRY, entryId)
            .putExtra(PACKAGE, form.target.packageName)
            .putExtra(SITE, (form.target as? FillTarget.WebPage)?.site?.text())
            .putExtra(PORT_KNOWN, (form.target as? FillTarget.WebPage)?.portKnown ?: false)
            .putExtra(FIELDS, fields.toIntArray())
            .putParcelableArrayListExtra(IDS, ArrayList(fields.map(ids::getValue)))
            .putExtra(USERNAMES, form.usernames.toIntArray())
            .putExtra(PASSWORDS, form.passwords.toIntArray())
            .putExtra(CODES, form.codes.toIntArray())
        val flags = PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(context, requestCodes.incrementAndGet(), intent, flags).intentSender
    }

    companion object {
        private const val MODE = "mode"
        private const val ENTRY = "entry"
        private const val PACKAGE = "package"
        private const val SITE = "site"
        private const val PORT_KNOWN = "portKnown"
        private const val FIELDS = "fields"
        private const val IDS = "ids"
        private const val USERNAMES = "usernames"
        private const val PASSWORDS = "passwords"
        private const val CODES = "codes"

        private val requestCodes = AtomicInteger()

        fun from(intent: Intent): AutofillRequest? {
            val mode = intent.getStringExtra(MODE)?.let { name -> AutofillMode.entries.firstOrNull { it.name == name } } ?: return null
            val packageName = intent.getStringExtra(PACKAGE) ?: return null
            val target = when (val site = intent.getStringExtra(SITE)) {
                null -> FillTarget.App(packageName)
                else -> FillTarget.WebPage(SiteAddress.parse(site) ?: return null, packageName, intent.getBooleanExtra(PORT_KNOWN, false))
            }
            val fields = intent.getIntArrayExtra(FIELDS) ?: return null
            val ids = intent.getParcelableArrayListExtra(IDS, AutofillId::class.java) ?: return null
            if (ids.size != fields.size) return null
            val form = AutofillForm(
                target = target,
                usernames = intent.getIntArrayExtra(USERNAMES)?.toList().orEmpty(),
                passwords = intent.getIntArrayExtra(PASSWORDS)?.toList().orEmpty(),
                codes = intent.getIntArrayExtra(CODES)?.toList().orEmpty(),
            )
            return AutofillRequest(mode, form, fields.toList().zip(ids).toMap(), intent.getStringExtra(ENTRY))
        }

        private fun SiteAddress.text(): String {
            val shownHost = if (':' in host) "[$host]" else host
            return (if (isSecure) "https" else "http") + "://" + shownHost + (port?.let { ":$it" }.orEmpty())
        }
    }
}

internal val FillTarget.packageName: String
    get() = when (this) {
        is FillTarget.App -> packageName
        is FillTarget.WebPage -> browserPackage
    }
