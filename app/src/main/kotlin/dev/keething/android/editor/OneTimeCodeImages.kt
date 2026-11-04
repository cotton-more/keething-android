package dev.keething.android.editor

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import dev.keething.android.sharing.CodeCamera
import dev.keething.android.sharing.QrReading
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A one-time code's QR code (keething-spec edit.one-time-code AC4, AC8): from an image file (SVG too), the clipboard's
 * image or the camera. Each hands the texts of the codes it finds to [onTexts], which takes the first that is a code
 * and says why not otherwise; a problem with the image itself goes to [onProblem].
 */
internal class CodeImages(
    val chooseFile: () -> Unit,
    val pasteImage: (Uri) -> Unit,
    val openCamera: () -> Unit,
)

@Composable
internal fun rememberCodeImages(onTexts: (List<String>) -> Unit, onProblem: (String) -> Unit, onCamera: () -> Unit): CodeImages {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val read = { uri: Uri ->
        scope.launch {
            val texts = withContext(Dispatchers.Default) { runCatching { QrReading.texts(context.contentResolver, uri) }.getOrNull() }
            if (texts == null) onProblem("The image could not be read.") else onTexts(texts)
        }
        Unit
    }
    // Any image file, from the phone or a cloud drive: GitHub's code is an SVG the photo picker does not offer.
    val chooseFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(read) }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onCamera() else onProblem("The camera is not allowed; allow it in the app's settings, or choose an image.")
    }
    return CodeImages(
        chooseFile = { chooseFile.launch(arrayOf("image/*")) },
        pasteImage = read,
        openCamera = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                onCamera()
            } else {
                askCamera.launch(Manifest.permission.CAMERA)
            }
        },
    )
}

/** What the clipboard holds: an image's address when it is an image, else its text (blank: nothing to paste). */
internal sealed interface Clipped {
    data class Image(val uri: Uri) : Clipped
    data class Text(val text: String) : Clipped
}

internal fun clipped(context: Context): Clipped {
    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 } ?: return Clipped.Text("")
    val item = clip.getItemAt(0)
    // An image's item would coerce to its content:// address as text.
    if (item.uri != null && clip.description.hasMimeType("image/*")) return Clipped.Image(item.uri)
    return Clipped.Text(item.coerceToText(context)?.toString().orEmpty())
}

/**
 * The camera, full screen, until a QR code that is a one-time code is seen: [onTexts] returns why the codes seen are
 * not taken (shown, the camera kept on), or null once one is.
 */
@Composable
internal fun CodeCameraDialog(onTexts: (List<String>) -> String?, onClose: () -> Unit) {
    var problem by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            CodeCamera(onTexts = { texts -> problem = onTexts(texts) }, modifier = Modifier.fillMaxSize())
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Color.White) }
                    Text("Scan the QR code", color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleLarge)
                }
                Box(Modifier.weight(1f))
                Text(
                    problem ?: "Point at the QR code the site shows for two-step sign-in.",
                    Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xCC101112)).padding(16.dp),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
