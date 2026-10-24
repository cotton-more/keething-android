package dev.keething.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dev.keething.android.navigation.KeethingApp
import dev.keething.android.navigation.NavigationState
import dev.keething.android.theme.KeethingTheme
import dev.keething.android.ui.secureWindow

class MainActivity : ComponentActivity() {
    private val container: AppContainer get() = (application as KeethingApplication).container

    private val navigation: NavigationState by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) follow(intent)
        secureWindow()
        enableEdgeToEdge()
        setContent {
            KeethingTheme {
                KeethingApp(container)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        follow(intent)
    }

    /** The "Codes" launcher shortcut (SPEC §6.2); the launcher icon itself opens the app as it was left. */
    private fun follow(intent: Intent) {
        if (intent.action == ACTION_CODES) navigation.showCodes()
    }

    companion object {
        /** The action of the "Codes" shortcut, in `res/xml/shortcuts.xml`. */
        const val ACTION_CODES = "dev.keething.android.action.CODES"
    }
}
