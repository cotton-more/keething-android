package dev.keething.android.autofill

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState

/**
 * The websites an app is verified for (Android app links, SPEC §7): the site's own
 * `/.well-known/assetlinks.json` names the app's signing key, and the system checked it when the app was
 * installed. Read offline from the system; only `verified` hosts count, not the ones the user merely chose
 * to open in the app.
 */
internal class AppWebsites(context: Context) {
    private val manager = context.getSystemService(DomainVerificationManager::class.java)

    /** Hosts as the app declares them, e.g. `www.lidl.de` or `*.mts.ru`; empty when not visible or none. */
    fun verifiedHosts(packageName: String): List<String> {
        val state = try {
            manager?.getDomainVerificationUserState(packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: SecurityException) {
            null
        } ?: return emptyList()
        return state.hostToStateMap.filterValues { it == DomainVerificationUserState.DOMAIN_STATE_VERIFIED }.keys.sorted()
    }
}
