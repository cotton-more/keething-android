package dev.keepassbar.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.keepassbar.android.storageprobe.StorageProbeScreen
import dev.keepassbar.android.theme.KeePassBarTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as KeePassBarApplication).container
        setContent {
            KeePassBarTheme {
                StorageProbeScreen(container)
            }
        }
    }
}
