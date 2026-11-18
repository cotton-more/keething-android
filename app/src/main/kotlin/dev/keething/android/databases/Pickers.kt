package dev.keething.android.databases

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import dev.keething.android.storage.PersistedGrants
import dev.keething.android.storage.ProviderFolder
import dev.keething.android.storage.StoragePlaces
import dev.keething.domain.storage.StoragePlace
import dev.keething.vault.databases.FileLocation
import androidx.compose.ui.res.stringResource
import dev.keething.android.R

/**
 * The system's folder picker, opening at a place (keething-spec databases.saf AC2): the folder chosen is granted for good
 * (read and write, kept across restarts) and given to [onPicked]. A folder of another app's provider is refused and said
 * (AC12). [onCancelled]: the picker closed without a folder (Android refuses the storage's top level and `Download`
 * itself there, AC3).
 */
@Composable
internal fun rememberFolderPicker(onCancelled: () -> Unit = {}, onPicked: (ProviderFolder) -> Unit): (start: StoragePlace?) -> Unit {
    val context = LocalContext.current
    val resources = LocalResources.current
    val picked = rememberUpdatedState(onPicked)
    val cancelled = rememberUpdatedState(onCancelled)
    var said by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(PickFolder) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult cancelled.value()
        val folder = ProviderFolder.of(context.contentResolver, uri.toString())
        if (folder == null) {
            said = resources.getString(R.string.databases_picker_not_phone_storage)
            return@rememberLauncherForActivityResult
        }
        said = context.take(uri, write = true)
        if (said == null) picked.value(folder)
    }
    said?.let { PickerSaid(it) { said = null } }
    return { start -> launcher.launch(start) }
}

/**
 * The system's file picker for a key file (keething-spec databases.saf AC9), any type, opening at a place: the file is
 * granted alone, for reading, kept across restarts; one in a folder the list reaches goes through that folder instead
 * (the list does that, and gives this grant back). Another app's provider is refused and said (AC12).
 */
@Composable
internal fun rememberKeyFilePicker(onPicked: (FileLocation.Granted) -> Unit): (start: StoragePlace?) -> Unit {
    val context = LocalContext.current
    val resources = LocalResources.current
    val picked = rememberUpdatedState(onPicked)
    var said by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(PickFile) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val place = StoragePlaces.ofDocument(uri)
        if (place == null) {
            said = resources.getString(R.string.databases_picker_not_phone_storage)
            return@rememberLauncherForActivityResult
        }
        said = context.take(uri, write = false)
        if (said == null) picked.value(FileLocation.Granted(uri.toString(), place))
    }
    said?.let { PickerSaid(it) { said = null } }
    return { start -> launcher.launch(start) }
}

/** What a picker's choice came to, in full until OK: a toast is cut at two lines. */
@Composable
private fun PickerSaid(text: String, onOk: () -> Unit) {
    AlertDialog(
        onDismissRequest = onOk,
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onOk) { Text(stringResource(R.string.databases_ok)) } },
    )
}

/** The picker's grant kept across restarts; null, or what to say when the system would not keep it. */
private fun Context.take(uri: Uri, write: Boolean): String? = try {
    PersistedGrants(contentResolver).take(uri, write)
    null
} catch (failure: SecurityException) {
    Log.w("Grants", "a grant could not be kept: ${failure.javaClass.simpleName}")
    getString(R.string.databases_picker_not_kept)
}

/** Open document tree, starting at a place. */
private object PickFolder : ActivityResultContract<StoragePlace?, Uri?>() {
    private val tree = ActivityResultContracts.OpenDocumentTree()

    override fun createIntent(context: Context, input: StoragePlace?): Intent = tree.createIntent(context, null).apply {
        StoragePlaces.initialUri(input)?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = tree.parseResult(resultCode, intent)
}

/** Open document, any type (a key file can be any file), starting at a place. */
private object PickFile : ActivityResultContract<StoragePlace?, Uri?>() {
    private val document = ActivityResultContracts.OpenDocument()

    override fun createIntent(context: Context, input: StoragePlace?): Intent = document.createIntent(context, arrayOf("*/*")).apply {
        StoragePlaces.initialUri(input)?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = document.parseResult(resultCode, intent)
}
