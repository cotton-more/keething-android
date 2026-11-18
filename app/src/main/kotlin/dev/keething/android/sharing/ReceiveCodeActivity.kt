package dev.keething.android.sharing

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import dev.keething.android.KeethingApplication
import dev.keething.android.MainActivity
import dev.keething.sharing.message.ShareCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Read with Keething" in the share sheet (SPEC §10): a picture of a code, several, or a text with codes, handed over by
 * a messenger or mail. Read here, while the other app's grant lasts, then handed to the main screen, which shows them
 * once the vault is unlocked; this screen shows nothing and goes. What it reads is a request or a sealed answer.
 */
class ReceiveCodeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handOver(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handOver(intent)
    }

    private fun handOver(intent: Intent) {
        val container = (application as KeethingApplication).container
        lifecycleScope.launch {
            val texts = withContext(Dispatchers.Default) { read(intent) }
            Log.i(TAG, "handed over: ${texts.size} texts")
            container.incomingCodes.value = texts.ifEmpty { listOf("") }
            startActivity(
                Intent(this@ReceiveCodeActivity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            finish()
        }
    }

    private fun read(intent: Intent): List<String> {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val pictures = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        return listOfNotNull(text) + pictures.filter(::isOthers).take(ShareCode.MAX_PARTS).flatMap { uri ->
            runCatching { QrReading.texts(contentResolver, uri) }.getOrDefault(emptyList())
        }
    }

    /**
     * A picture another app offers through its own provider. Not a `file:` path nor one of this app's providers: the
     * app reads with its own rights (all files, its private ones), which the sender must not borrow.
     */
    private fun isOthers(uri: Uri): Boolean =
        uri.scheme == "content" && uri.authority?.startsWith(packageName) == false

    private companion object {
        const val TAG = "Sharing" // not user text
    }
}
