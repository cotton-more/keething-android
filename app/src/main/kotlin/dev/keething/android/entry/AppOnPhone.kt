package dev.keething.android.entry

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri

/**
 * What this phone has of an app an entry names (keething-spec card.app-address): read from `PackageManager` each time
 * the card shows the row (`QUERY_ALL_PACKAGES`, SPEC §7), never fetched, never written to the database.
 */
internal sealed interface AppOnPhone {
    /** [launch] is null for an app that cannot be started (no launcher activity, AC3). */
    class Installed(val name: String, val icon: ImageBitmap, val launch: Intent?) : AppOnPhone

    /** Not installed, or not visible to Keething (AC4); [store] finds it, null when no app opens store links (AC5). */
    class Missing(val store: Store?) : AppOnPhone

    /** The way to the app's page in a store: `market://details?id=<package>`. */
    sealed interface Store {
        val intent: Intent

        /** One app opens store links: its own icon and name on the button (Google Play, F-Droid, …). */
        class One(override val intent: Intent, val name: String, val icon: ImageBitmap) : Store

        /** Several do: the system asks which. */
        class Several(override val intent: Intent) : Store
    }

    companion object {
        fun of(context: Context, packageName: String, iconSize: Int): AppOnPhone {
            val pm = context.packageManager
            val info = try {
                pm.getApplicationInfo(packageName, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                return Missing(store(pm, packageName, iconSize))
            }
            return Installed(pm.getApplicationLabel(info).toString(), pm.getApplicationIcon(info).image(iconSize), pm.getLaunchIntentForPackage(packageName))
        }

        private fun store(pm: PackageManager, packageName: String, iconSize: Int): Store? {
            val intent = Intent(Intent.ACTION_VIEW, "market://details?id=$packageName".toUri()) // not user text
            val stores = pm.queryIntentActivities(intent, 0)
            return when (stores.size) {
                0 -> null
                1 -> Store.One(intent, stores[0].loadLabel(pm).toString(), stores[0].loadIcon(pm).image(iconSize))
                else -> Store.Several(intent)
            }
        }

        private fun Drawable.image(size: Int): ImageBitmap = toBitmap(size, size).asImageBitmap()
    }
}
