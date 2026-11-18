package dev.keething.android.databases

import android.app.Activity
import android.content.ContentResolver
import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.keething.android.AppContainer
import dev.keething.android.storage.PersistedGrants
import dev.keething.android.storage.ProviderFolder
import dev.keething.android.theme.extraColors
import dev.keething.android.ui.SegmentGap
import dev.keething.android.ui.segmentShape
import dev.keething.domain.storage.FolderItem
import dev.keething.domain.storage.StoragePlace
import dev.keething.domain.storage.folderOf
import dev.keething.domain.storage.pathIn
import dev.keething.vault.databases.Databases
import dev.keething.vault.databases.FileLocation
import java.io.IOException
import java.nio.file.AccessDeniedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource
import dev.keething.android.R

/** A folder the user granted: its grant and where it is on the storage. */
internal data class GrantedPlace(val grant: String, val place: StoragePlace)

/** The folders the list's databases and key files are reached through, in the list's order (keething-spec databases.saf AC3). */
internal fun Databases.grantedFolders(): List<GrantedPlace> =
    records.flatMap { listOfNotNull(it.file, it.keyFile) }
        .filterIsInstance<FileLocation.InFolder>()
        .map { GrantedPlace(it.grant, it.folder) }
        .distinctBy { it.grant }

/** What is in a folder as the sheet lists it. */
internal sealed interface FolderListing {
    /** Its folders (Syncthing's own and hidden ones aside), then its KeePass files (keething-spec databases.saf AC3). */
    data class Items(val folders: List<String>, val files: List<String>) : FolderListing

    data object Gone : FolderListing

    data object NoAccess : FolderListing

    data class Failed(val why: String) : FolderListing

    companion object {
        /** `.kdbx` files, case aside; hidden ones and Syncthing's own (`.stfolder`, `~syncthing~…`) aside; by name. */
        fun of(items: List<FolderItem>): Items {
            val shown = items.filterNot { it.name.startsWith(".") || it.name.startsWith("~syncthing~") }
            return Items(
                folders = shown.filter { it.isFolder }.map { it.name }.sortedWith(String.CASE_INSENSITIVE_ORDER),
                files = shown.filter { !it.isFolder && it.name.endsWith(".kdbx", ignoreCase = true) }.map { it.name }.sortedWith(String.CASE_INSENSITIVE_ORDER),
            )
        }
    }
}

/** Where the sheet is: in a granted folder, at a path inside it. */
private data class Browsing(val folder: GrantedPlace, val path: String)

/**
 * Open a database… and Locate… (SPEC §6.2; keething-spec databases.saf AC2, AC3, AC10, AC13): the folders the list
 * already reaches, each opened into its folders and KeePass files, and Choose a folder… for the system's picker, then the
 * files of the folder chosen. A folder chosen there that a granted one still held holds goes through that one: nothing
 * more is kept. [title]: "Open a database", "Locate Personal.kdbx"; the picker opens at [pickAt], at once when
 * [pickAtOnce] (nothing granted yet, a folder whose access is gone, an old record's folder). [onFolderPicked]: a folder
 * the picker gave, first (an old record's: null when that settles it, and the sheet closes; else why not, said over its
 * files). [onChosen]: a KeePass file tapped; the sheet closes then. Every grant the picker gave here is offered to the list
 * as it closes, or as it goes otherwise: those it does not take are given back (AC14). Kept across a rotation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChooseDatabaseSheet(
    container: AppContainer,
    title: String,
    pickAt: StoragePlace?,
    pickAtOnce: Boolean,
    onChosen: (FileLocation.InFolder) -> Unit,
    onDismiss: () -> Unit,
    onFolderPicked: ((ProviderFolder) -> String?)? = null,
) {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val root = remember { Environment.getExternalStorageDirectory().path }
    val databases by container.sessions.list.state.collectAsStateWithLifecycle()
    // Every grant the picker gave here, and those among them no granted one holds (shown with the list's folders), by
    // their addresses: kept across a rotation, as is where the sheet is.
    var taken by rememberSaveable { mutableStateOf(listOf<String>()) }
    var added by rememberSaveable { mutableStateOf(listOf<String>()) }
    var browsingGrant by rememberSaveable { mutableStateOf<String?>(null) }
    var browsingPath by rememberSaveable { mutableStateOf("") }
    var cancelled by rememberSaveable { mutableStateOf(false) }
    var asked by rememberSaveable { mutableStateOf(false) }
    var folderProblem by rememberSaveable { mutableStateOf<String?>(null) }
    val folders = (databases.grantedFolders() + added.mapNotNull { grant -> ProviderFolder.of(resolver, grant)?.let { GrantedPlace(grant, it.place) } })
        .distinctBy { it.grant }
    val browsing = browsingGrant?.let { grant -> folders.firstOrNull { it.grant == grant } }?.let { Browsing(it, browsingPath) }
    val browse = { to: Browsing? ->
        browsingGrant = to?.folder?.grant
        browsingPath = to?.path.orEmpty()
    }
    var closed by remember { mutableStateOf(false) }
    val close = { done: (() -> Unit)? ->
        closed = true
        taken.forEach(container::offerGrant)
        done?.invoke()
        onDismiss()
    }
    // Gone without closing (another screen in its place): what it took goes back too, unless it only turns.
    DisposableEffect(Unit) {
        onDispose {
            if (!closed && (context as? Activity)?.isChangingConfigurations != true) taken.forEach(container::offerGrant)
        }
    }
    val pick = rememberFolderPicker(onCancelled = { cancelled = true }) { picked ->
        cancelled = false
        folderProblem = null
        container.grantTaken(picked.id)
        taken = taken + picked.id
        onFolderPicked?.let { settle ->
            val problem = settle(picked)
            if (problem == null) return@rememberFolderPicker close(null)
            // Not that one's folder: its files are shown, to choose the database there (it is located, AC13); the grant,
            // offered by the refusal, is still in use here.
            container.grantTaken(picked.id)
            folderProblem = problem
        }
        // A folder a granted one the app still holds holds: through that one (AC3); the new grant goes back as the sheet
        // closes. A grant gone (no access) is no way in.
        val held = PersistedGrants(resolver).held()
        val holder = folders.firstOrNull { it.grant in held && picked.place.inside(it.place) != null }
        if (holder != null) {
            browse(Browsing(holder, picked.place.inside(holder.place).orEmpty()))
        } else {
            added = added + picked.id
            browse(Browsing(GrantedPlace(picked.id, picked.place), ""))
        }
    }
    LaunchedEffect(Unit) {
        if (pickAtOnce && !asked) {
            asked = true
            pick(pickAt)
        }
    }

    val at = browsing
    val listing by produceState<FolderListing?>(null, at) {
        value = null
        if (at != null) value = withContext(Dispatchers.IO) { listing(resolver, at) }
    }

    ModalBottomSheet(onDismissRequest = { close(null) }, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (at != null) {
                    IconButton(onClick = { browse(if (at.path.isEmpty()) null else at.copy(path = folderOf(at.path))) }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.databases_choose_up))
                    }
                }
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (at != null) {
                        Text(
                            DatabaseText.words(at.folder.place.resolve(at.path), root),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.MiddleEllipsis,
                        )
                    }
                }
            }
            folderProblem?.let { Note(it, isProblem = true) }
            if (at == null) {
                if (folders.isNotEmpty()) {
                    Rows(folders.map { folder -> Item(Icons.Rounded.Folder, DatabaseText.words(folder.place, root)) { browse(Browsing(folder, "")) } })
                }
            } else {
                when (val shown = listing) {
                    null -> Note(stringResource(R.string.databases_choose_looking))
                    FolderListing.Gone -> Note(stringResource(R.string.databases_choose_folder_gone))
                    FolderListing.NoAccess -> Note(stringResource(R.string.databases_choose_no_access))
                    is FolderListing.Failed -> Note(stringResource(R.string.databases_choose_folder_failed, shown.why))
                    is FolderListing.Items -> {
                        val resources = LocalResources.current
                        val rows = shown.folders.map { name -> Item(Icons.Rounded.Folder, name) { browse(at.copy(path = pathIn(at.path, name))) } } +
                            shown.files.map { name ->
                                val file = FileLocation.InFolder(at.folder.grant, at.folder.place, pathIn(at.path, name))
                                val listed = databases.records.firstOrNull { it.file.isSameFile(file) }
                                Item(Icons.Rounded.Description, name, listed?.let { resources.getString(R.string.databases_choose_in_list, it.displayName) }) {
                                    close { onChosen(file) }
                                }
                            }
                        if (rows.isEmpty()) Note(stringResource(R.string.databases_choose_no_files)) else Rows(rows)
                    }
                }
            }
            ChooseFolder(
                text = stringResource(if (folders.isEmpty() && at == null) R.string.databases_choose_folder else R.string.databases_choose_another_folder),
                cancelled = cancelled,
                onClick = { pick(at?.folder?.place?.resolve(at.path) ?: pickAt) },
            )
        }
    }
}

/** What is at [at], asked of its folder's provider. Off the main thread. */
private fun listing(resolver: ContentResolver, at: Browsing): FolderListing = try {
    val folder = ProviderFolder.of(resolver, at.folder.grant) ?: throw AccessDeniedException(at.folder.grant)
    folder.list(at.path)?.let(FolderListing::of) ?: FolderListing.Gone
} catch (_: AccessDeniedException) {
    FolderListing.NoAccess
} catch (failure: IOException) {
    FolderListing.Failed(failure.message ?: failure.javaClass.simpleName)
}

private class Item(val icon: ImageVector, val text: String, val detail: String? = null, val onClick: () -> Unit)

@Composable
private fun Rows(rows: List<Item>) {
    Column(Modifier.heightIn(max = 362.dp).clip(RoundedCornerShape(20.dp)).verticalScroll(rememberScrollState())) {
        rows.forEachIndexed { index, row ->
            Row(
                Modifier
                    .padding(bottom = SegmentGap)
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clip(segmentShape(index, rows.size))
                    .background(MaterialTheme.extraColors.row)
                    .clickable(onClick = row.onClick)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(row.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f)) {
                    Text(row.text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    row.detail?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun Note(text: String, isProblem: Boolean = false) {
    Text(
        text,
        Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = if (isProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Choose a folder…, and what Android gives (keething-spec databases.saf AC3): not the storage's top level nor `Download`
 * itself, which its picker refuses; said in the error colour once the picker closed without a folder.
 */
@Composable
private fun ChooseFolder(text: String, cancelled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.databases_refused_folders),
                style = MaterialTheme.typography.bodySmall,
                color = if (cancelled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

