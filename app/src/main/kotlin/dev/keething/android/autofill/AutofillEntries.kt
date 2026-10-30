package dev.keething.android.autofill

import android.content.Context
import android.content.pm.PackageManager
import dev.keething.autofill.matching.AutofillCandidate
import dev.keething.autofill.matching.AutofillMatcher
import dev.keething.autofill.form.AutofillForm
import dev.keething.autofill.target.FillTarget
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.Vault
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.EntryRef

/** An open database as autofill reads it. */
internal class OpenVault(val id: DatabaseId, val vault: Vault)

/**
 * The entries offered for a form (SPEC §7), from every open database (keething-spec autofill.fill AC6), each a copy
 * whose id is its ref: the matcher is built once per set of vaults read, and dropped on a lock with them.
 */
internal class AutofillEntries(private val context: Context) {
    private val websites = AppWebsites(context)
    private var vaults: List<Vault> = emptyList()
    private var matcher: AutofillMatcher? = null

    /** Best first; for a form that asks only for a one-time code, only the entries that have one. */
    fun candidates(open: List<OpenVault>, form: AutofillForm): List<AutofillCandidate> =
        candidates(open, form.target).filter { !form.asksOnlyForCode || it.entry.totp != null }

    /** The entries of [target], best first. */
    fun candidates(open: List<OpenVault>, target: FillTarget): List<AutofillCandidate> {
        if (open.isEmpty()) return emptyList()
        val appWebsites = if (target is FillTarget.App) websites.verifiedHosts(target.packageName) else emptyList()
        return matcher(open).candidates(target, appWebsites)
    }

    /** What the user sees as the target: the page's host, or the app's name. */
    fun label(target: FillTarget): String = when (target) {
        is FillTarget.WebPage -> target.site.host
        is FillTarget.App -> appName(target.packageName) ?: target.packageName
    }

    fun clear() {
        synchronized(this) {
            vaults = emptyList()
            matcher = null
        }
    }

    private fun matcher(open: List<OpenVault>): AutofillMatcher = synchronized(this) {
        val read = open.map { it.vault }
        matcher?.takeIf { vaults.size == read.size && vaults.indices.all { vaults[it] === read[it] } } ?: AutofillMatcher(entriesOf(open)).also {
            vaults = read
            matcher = it
        }
    }

    companion object {
        /** The open databases' entries in the window's order, each a copy whose id is its ref (databases.one-list AC1). */
        fun entriesOf(open: List<OpenVault>): List<Entry> =
            open.flatMap { database -> database.vault.entries.map { it.copy(id = EntryRef(database.id, it.id).text) } }
    }

    private fun appName(packageName: String): String? = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
}
