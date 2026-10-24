package dev.keething.android.autofill

import android.content.Context
import android.content.pm.PackageManager
import dev.keething.autofill.matching.AutofillCandidate
import dev.keething.autofill.matching.AutofillMatcher
import dev.keething.autofill.form.AutofillForm
import dev.keething.autofill.target.FillTarget
import dev.keething.domain.vault.Vault

/**
 * The entries offered for a form (SPEC §7): the matcher is built once per vault that was read, and dropped
 * on a lock with the vault.
 */
internal class AutofillEntries(private val context: Context) {
    private val websites = AppWebsites(context)
    private var vault: Vault? = null
    private var matcher: AutofillMatcher? = null

    /** Best first; for a form that asks only for a one-time code, only the entries that have one. */
    fun candidates(vault: Vault, form: AutofillForm): List<AutofillCandidate> =
        candidates(vault, form.target).filter { !form.asksOnlyForCode || it.entry.totp != null }

    /** The entries of [target], best first. */
    fun candidates(vault: Vault, target: FillTarget): List<AutofillCandidate> {
        val appWebsites = if (target is FillTarget.App) websites.verifiedHosts(target.packageName) else emptyList()
        return matcher(vault).candidates(target, appWebsites)
    }

    /** What the user sees as the target: the page's host, or the app's name. */
    fun label(target: FillTarget): String = when (target) {
        is FillTarget.WebPage -> target.site.host
        is FillTarget.App -> appName(target.packageName) ?: target.packageName
    }

    fun clear() {
        synchronized(this) {
            vault = null
            matcher = null
        }
    }

    private fun matcher(vault: Vault): AutofillMatcher = synchronized(this) {
        matcher?.takeIf { this.vault === vault } ?: AutofillMatcher(vault.entries).also {
            this.vault = vault
            matcher = it
        }
    }

    private fun appName(packageName: String): String? = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
}
