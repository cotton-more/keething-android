package dev.keething.autofill.saving

import dev.keething.autofill.matching.AndroidAppAddress
import dev.keething.autofill.target.FillTarget
import dev.keething.domain.vault.Entry

/** A login as typed into a form: what a save request carries (SPEC §7, Save). */
public data class TypedLogin(
    val username: String,
    /** The password to keep: the new one of a sign-up or a password change, else the one typed. */
    val password: String,
    /** A password change's current password: tells which entry changes when no username was typed. */
    val currentPassword: String? = null,
)

/** What saving a typed login does. */
public sealed interface SavePlan {
    /** The entry already has this password: nothing to save. */
    public data class Unchanged(val entry: Entry) : SavePlan

    /** The entry of this account gets the new password. */
    public data class Update(val entry: Entry) : SavePlan

    /** A new entry for the site or app. */
    public data class New(val title: String, val url: String) : SavePlan
}

public object SavePlanner {
    /**
     * [candidates]: the entries autofill offers for [target], best first. The account is the candidate with
     * the typed username (in any case); without one, the candidate whose password was typed as the current
     * one, or the only candidate. Another username on the same site is another account: a new entry.
     * [appName]: the app's name, the new entry's title for an app.
     */
    public fun plan(candidates: List<Entry>, typed: TypedLogin, target: FillTarget, appName: String?): SavePlan {
        val account = when {
            typed.username.isNotEmpty() -> candidates.firstOrNull { it.username.equals(typed.username, ignoreCase = true) }
            typed.currentPassword != null -> candidates.firstOrNull { it.password.reveal() == typed.currentPassword }
            else -> null
        } ?: candidates.singleOrNull()?.takeIf { typed.username.isEmpty() }
        return when {
            account == null -> SavePlan.New(title(target, appName), url(target))
            account.password.reveal() == typed.password -> SavePlan.Unchanged(account)
            else -> SavePlan.Update(account)
        }
    }

    /** A page's host without `www.`, or the app's name (its package when it has none). */
    private fun title(target: FillTarget, appName: String?): String = when (target) {
        is FillTarget.WebPage -> target.site.host.removePrefix("www.")
        is FillTarget.App -> appName ?: target.packageName
    }

    /** The page's address (`https://www.shop.example`), or `androidapp://<package>`, as Keepass2Android writes it. */
    private fun url(target: FillTarget): String = when (target) {
        is FillTarget.WebPage -> with(target.site) {
            (if (isSecure) "https" else "http") + "://" + (if (':' in host) "[$host]" else host) + (port?.let { ":$it" }.orEmpty())
        }
        is FillTarget.App -> AndroidAppAddress.SCHEME + target.packageName
    }
}
