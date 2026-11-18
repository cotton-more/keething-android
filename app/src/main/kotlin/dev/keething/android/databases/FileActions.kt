package dev.keething.android.databases

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import dev.keething.android.AppContainer
import dev.keething.android.R
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.FileLocation

/** What a screen does with the list's files: Open a database…, Locate… (or an old record's folder), Choose key file…. */
internal class FileActions(
    /** Open a database…: its sheet; the picker at once while nothing is granted. */
    val open: () -> Unit,
    /** Locate… a database ([pickNow]: its folder's access is gone, the picker opens at once); for one kept by its path, its folder (AC13). */
    val locate: (id: DatabaseId, pickNow: Boolean) -> Unit,
    /** Choose key file… for a database: the file picker at its key file's folder, else its own. */
    val chooseKeyFile: (DatabaseId) -> Unit,
)

/** The sheet shown, as kept across a rotation: Open a database…, Locate… of a database (the picker at once or not). */
private sealed interface Shown {
    data object Open : Shown

    data class Locate(val id: DatabaseId, val pickNow: Boolean) : Shown

    fun saved(): String = when (this) {
        Open -> "open"
        is Locate -> "locate${if (pickNow) "!" else ""}:${id.value}"
    }

    companion object {
        fun of(saved: String): Shown? = when {
            saved == "open" -> Open
            saved.startsWith("locate") -> Locate(DatabaseId(saved.substringAfter(':')), pickNow = saved.startsWith("locate!"))
            else -> null
        }
    }
}

/**
 * The list's files reached as keething-spec databases.saf has it (SPEC §6.2), through [ChooseDatabaseSheet]: Open a
 * database… and Locate… (a folder, then its KeePass files, AC2, AC3, AC10), and an old record's Locate…, its folder asked
 * in the picker opened at it (AC13): a folder that holds it settles it, else its file is chosen there and located (a
 * database directly in `Download`, whose folder Android gives no app); a key file in the file picker (AC9). Shows the sheet
 * while one is open; what is asked survives a rotation. [onOpened]: a database opened (it is current); [onProblem]: what
 * Locate… or a folder chosen could not do, null when it did.
 */
@Composable
internal fun rememberFileActions(
    container: AppContainer,
    onOpened: (DatabaseId) -> Unit = {},
    onProblem: (String?) -> Unit = {},
): FileActions {
    val databases = container.sessions.databases
    val list = container.sessions.list
    val opened by rememberUpdatedState(onOpened)
    val problem by rememberUpdatedState(onProblem)
    var shown by rememberSaveable { mutableStateOf<String?>(null) }
    // Whose key file the picker is asked for; one that comes back without it (the process ended meanwhile) gives its grant back.
    var keyFileOf by rememberSaveable { mutableStateOf<String?>(null) }
    val resources = LocalResources.current

    val pickKeyFile = rememberKeyFilePicker { picked ->
        val id = keyFileOf ?: return@rememberKeyFilePicker container.offerGrant(picked.grant)
        keyFileOf = null
        databases.setKeyFile(DatabaseId(id), picked)
    }

    when (val sheet = shown?.let(Shown::of)) {
        null -> Unit
        Shown.Open -> {
            val now = list.state.value
            ChooseDatabaseSheet(
                container = container,
                title = stringResource(R.string.databases_open_title),
                pickAt = (now.current?.file as? FileLocation.InFolder)?.place?.parent,
                pickAtOnce = now.grantedFolders().isEmpty(),
                onChosen = { file -> opened(databases.open(file)) },
                onDismiss = { shown = null },
            )
        }
        is Shown.Locate -> {
            val record = list.record(sheet.id)
            if (record == null) {
                shown = null
            } else {
                val old = record.file is FileLocation.ByPath
                ChooseDatabaseSheet(
                    container = container,
                    title = stringResource(if (old) R.string.databases_locate_folder_title else R.string.databases_locate_title, record.fileName),
                    pickAt = record.file.place?.parent,
                    pickAtOnce = sheet.pickNow || old,
                    onChosen = { file -> problem(databases.locate(sheet.id, file)) },
                    onDismiss = { shown = null },
                    onFolderPicked = if (old) {
                        { folder ->
                            databases.folderGranted(sheet.id, folder.id, folder.place)?.let { resources.getString(R.string.databases_locate_choose_below, it) }
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }

    return remember(container) {
        FileActions(
            open = { shown = Shown.Open.saved() },
            locate = { id, pickNow ->
                problem(null)
                shown = Shown.Locate(id, pickNow).saved()
            },
            chooseKeyFile = { id ->
                val record = list.record(id)
                if (record != null) {
                    keyFileOf = id.value
                    pickKeyFile((record.keyFile?.takeUnless { it is FileLocation.ByPath } ?: record.file).place?.parent)
                }
            },
        )
    }
}
