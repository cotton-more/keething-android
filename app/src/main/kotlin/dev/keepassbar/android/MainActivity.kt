package dev.keepassbar.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.keepassbar.android.navigation.KeePassBarApp
import dev.keepassbar.android.theme.KeePassBarTheme
import dev.keepassbar.android.ui.secureWindow

class MainActivity : ComponentActivity() {
    private val container: AppContainer get() = (application as KeePassBarApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secureWindow()
        enableEdgeToEdge()
        setContent {
            KeePassBarTheme {
                KeePassBarApp(container)
            }
        }
    }
}
