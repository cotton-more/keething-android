package dev.keething.android.groups

import dev.keething.android.session.file
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import dev.keething.android.editor.NewEntryDatabase
import dev.keething.android.editor.SAVE_LOCKED
import dev.keething.android.editor.SaveIntoSheet
import dev.keething.android.editor.problem
import dev.keething.android.editor.savedNotice
import dev.keething.android.editor.syncWarning
import androidx.annotation.StringRes
import dev.keething.android.R
import dev.keething.android.ui.NoLearningKeyboard
import dev.keething.android.ui.Notice
import dev.keething.android.ui.NoticeEffect
import dev.keething.android.ui.NoticeHost
import dev.keething.android.ui.PathText
import dev.keething.android.ui.UiText
import dev.keething.android.ui.keepOutOfAutofill
import dev.keething.android.ui.resolve
import dev.keething.android.ui.uiText
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.keething.card.editing.GroupChanges
import dev.keething.card.editing.GroupTree
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.NewGroup
import dev.keething.domain.vault.VaultEdit
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import dev.keething.android.databases.DatabaseLabelText
import dev.keething.android.databases.NotOpenDatabase
import dev.keething.android.databases.ReadOnlyMark
import dev.keething.android.databases.databaseColors
import dev.keething.android.session.OpenDatabase
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.DatabaseSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.keething.android.list.SectionTitle
import dev.keething.android.ui.BarTitle
import dev.keething.android.theme.extraColors
import dev.keething.android.ui.SegmentGap
import dev.keething.android.ui.readableInset
import dev.keething.android.ui.segmentShape
import java.text.NumberFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * A block per open database, in the window's order (keething-spec plans/android-groups.md G7): each built again when its
 * database is read again (a save, a sync), gone when it is closed or locked alone, there when one opens.
 *
 * The groups' changes (G5; groups.manage): a long press opens a group's [sheet]; New group and Rename ask for a name in a
 * [dialog], Move to… opens the picker ([moving]), Delete goes at once. Each is asked of the database's groups as they
 * are now ([GroupChanges]), then saved at once through the checked save, one at a time: the row says "Saving…"
 * ([saving]), the snackbar how it went; a failure keeps the dialog with the reason. Overlays of a database that closes go.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class GroupsViewModel(
    private val open: StateFlow<List<OpenDatabase>>,
    /** A change tried on a database read-only (KDBX 3.1, or too large to save): refused before it starts. */
    private val readOnly: (DatabaseSession) -> Unit = {},
) : ViewModel() {
    private val built = GroupsBlocks()

    val blocks: StateFlow<List<GroupsBlock>?> = open
        .map { databases -> databases.map { it.mark to it.unlocked.vault } }
        .distinctUntilChanged { a, b -> a.size == b.size && a.indices.all { a[it].first == b[it].first && a[it].second === b[it].second } }
        .mapLatest { databases -> withContext(Dispatchers.Default) { built.of(databases) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The open databases that are read-only: their headings show it (keething-spec databases.read-only-mark AC1). */
    val readOnlyIds: StateFlow<Set<DatabaseId>> = open
        .map { databases -> databases.filter { it.unlocked.readOnly }.map { it.database.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** A group of an open database. */
    data class GroupRef(val database: DatabaseId, val groupId: String)

    /** The name's dialog: a group renamed, or a new one made in [parentId]. */
    sealed interface NameDialog {
        val database: DatabaseId

        data class Rename(override val database: DatabaseId, val groupId: String) : NameDialog

        data class New(override val database: DatabaseId, val parentId: String) : NameDialog
    }

    var sheet: GroupRef? by mutableStateOf(null)
        private set
    var dialog: NameDialog? by mutableStateOf(null)
        private set

    /** Under the dialog's field: why the name is refused, or why its save failed. */
    var dialogProblem: UiText? by mutableStateOf(null)
        private set
    var moving: GroupRef? by mutableStateOf(null)
        private set

    /** New group in the bar while two or more are open: which database first. */
    var choosingDatabase: Boolean by mutableStateOf(false)
        private set

    /** The row whose change is being saved. */
    var saving: GroupRef? by mutableStateOf(null)
        private set

    private val mutableNotices = Channel<Notice>(Channel.BUFFERED)
    val notices: Flow<Notice> = mutableNotices.receiveAsFlow()

    init {
        viewModelScope.launch {
            open.collect { databases ->
                val ids = databases.mapTo(HashSet()) { it.mark.id }
                if (sheet?.database !in ids) sheet = null
                if (moving?.database !in ids) moving = null
                if (dialog?.database !in ids) closeDialog()
                if (databases.size < 2) choosingDatabase = false
            }
        }
    }

    /** Its changes (the sheet holds only those); a database read-only says why instead. */
    fun openSheet(ref: GroupRef) {
        val database = open.value.firstOrNull { it.mark.id == ref.database } ?: return
        if (database.unlocked.readOnly) return readOnly(database.database)
        sheet = ref
    }

    fun closeSheet() {
        sheet = null
    }

    fun startRename(ref: GroupRef) {
        sheet = null
        dialogProblem = null
        dialog = NameDialog.Rename(ref.database, ref.groupId)
    }

    fun startNewGroup(ref: GroupRef) {
        sheet = null
        choosingDatabase = false
        dialogProblem = null
        dialog = NameDialog.New(ref.database, ref.groupId)
    }

    /** The bar's New group: at the top level, of the one database that takes it or of the one chosen. */
    fun newGroupInBar() {
        val databases = open.value.filterNot { it.unlocked.readOnly }
        when {
            databases.size >= 2 -> choosingDatabase = true
            databases.isEmpty() -> open.value.firstOrNull()?.let { readOnly(it.database) }
            else -> newGroupAtTop(databases.first())
        }
    }

    fun chooseDatabase(id: DatabaseId) {
        choosingDatabase = false
        open.value.firstOrNull { it.mark.id == id }?.let(::newGroupAtTop)
    }

    fun dismissDatabases() {
        choosingDatabase = false
    }

    private fun newGroupAtTop(database: OpenDatabase) {
        val root = database.unlocked.vault.groups.firstOrNull { it.isRoot } ?: return
        startNewGroup(GroupRef(database.mark.id, root.id))
    }

    /** The name typed changed: the reason under it goes. */
    fun edited() {
        dialogProblem = null
    }

    /** Cancel, or Back: not while its save runs. */
    fun closeDialog() {
        if (saving != null && dialog != null && open.value.any { it.mark.id == dialog?.database }) return
        dialog = null
        dialogProblem = null
    }

    /** Save in the dialog: refused under the field, closed for the same name, else saved (AC1, AC3, AC8). */
    fun submitName(typed: String) {
        val dialog = dialog ?: return
        if (saving != null) return
        val database = open.value.firstOrNull { it.mark.id == dialog.database } ?: return closeDialog()
        val vault = database.unlocked.vault
        val changes = GroupChanges.of(vault)
        val group = { id: String -> vault.groups.firstOrNull { it.id == id } }
        when (dialog) {
            is NameDialog.Rename -> when (val name = changes.rename(dialog.groupId, typed)) {
                null -> dialogProblem = GONE
                GroupChanges.Name.Unchanged -> closeDialog()
                is GroupChanges.Name.Ok ->
                    save(database, VaultEdit.RenameGroup(dialog.groupId, name.name), dialog.groupId, GroupTexts.renamed(name.name))
                else -> dialogProblem = GroupTexts.nameProblem(name)
            }
            is NameDialog.New -> when (val name = changes.create(dialog.parentId, typed)) {
                null -> dialogProblem = GONE
                is GroupChanges.Name.Ok -> save(
                    database,
                    VaultEdit.CreateGroup(dialog.parentId, NewGroup(Entry.newId(), name.name)),
                    dialog.parentId,
                    GroupTexts.made(name.name, group(dialog.parentId)!!),
                )
                else -> dialogProblem = GroupTexts.nameProblem(name)
            }
        }
    }

    fun startMove(ref: GroupRef) {
        sheet = null
        moving = ref
    }

    fun closeMove() {
        moving = null
    }

    /** Move to…'s target chosen: refused targets cannot be (the picker greys them), so this one is asked again only. */
    fun move(targetId: String) {
        val ref = moving ?: return
        moving = null
        val database = open.value.firstOrNull { it.mark.id == ref.database } ?: return
        val vault = database.unlocked.vault
        val group = vault.groups.firstOrNull { it.id == ref.groupId }
        val target = vault.groups.firstOrNull { it.id == targetId }
        if (group == null || target == null || !GroupChanges.of(vault).canMove(ref.groupId, targetId)) return notice(GONE)
        save(database, VaultEdit.MoveGroup(ref.groupId, targetId), ref.groupId, GroupTexts.moved(group, target))
    }

    /** Delete on an empty group's sheet: at once, nothing is lost (AC6). */
    fun delete(ref: GroupRef) {
        sheet = null
        val database = open.value.firstOrNull { it.mark.id == ref.database } ?: return
        val vault = database.unlocked.vault
        val group = vault.groups.firstOrNull { it.id == ref.groupId }
        if (group == null || !GroupChanges.of(vault).canDelete(ref.groupId)) return notice(GONE)
        save(database, VaultEdit.DeleteEmptyGroup(ref.groupId), ref.groupId, GroupTexts.deleted(group))
    }

    private fun save(database: OpenDatabase, edit: VaultEdit, row: String, done: UiText) {
        val inDialog = dialog != null
        val pending = database.database.session.save(edit)
        if (pending == null) {
            if (inDialog) dialogProblem = SAVE_LOCKED else notice(SAVE_LOCKED)
            return
        }
        saving = GroupRef(database.mark.id, row)
        viewModelScope.launch {
            val outcome = pending.await()
            val problem = outcome.problem(database.database.file)
            saving = null
            when {
                problem == null -> {
                    if (inDialog) closeDialog()
                    mutableNotices.trySend(savedNotice(done, outcome.syncWarning))
                }
                inDialog && dialog != null -> dialogProblem = problem
                else -> notice(problem)
            }
        }
    }

    private fun notice(problem: UiText) {
        mutableNotices.trySend(Notice(problem, isProblem = true))
    }

    private companion object {
        val GONE: UiText = uiText(R.string.groups_gone)
    }
}

/**
 * Groups & tags (SPEC §6.2): a block per open database, even with one, its header pinned while its block scrolls; in it
 * the group tree with how many entries are in each group, the recycle bin, the tags with their counts. A row lists its
 * entries: [onSearch] gets the search to set on the list, that database's only while two or more are open. With two or
 * more a header's tap folds its block ([folded], kept while the app runs). Under the blocks the window's databases
 * [notOpen]; a tap shows one's lock screen ([onLockScreen]). A long press on a group's row opens its sheet: its changes
 * ([GroupsViewModel]); New group in the bar makes one at the top level, with two or more open in the one chosen of
 * [databases].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GroupsScreen(
    open: StateFlow<List<OpenDatabase>>,
    notOpen: List<NotOpenDatabase>,
    folded: MutableStateFlow<Set<DatabaseId>>,
    onBack: () -> Unit,
    onSearch: (String) -> Unit,
    onLockScreen: (DatabaseId) -> Unit,
    databases: (List<OpenDatabase>) -> List<NewEntryDatabase>,
    /** A change tried on a database read-only: [dev.keething.android.session.Sessions.readOnlyRefusal]. */
    onReadOnly: (DatabaseSession) -> Unit = {},
) {
    val model = viewModel { GroupsViewModel(open, onReadOnly) }
    val blocks by model.blocks.collectAsStateWithLifecycle()
    val readOnlyIds by model.readOnlyIds.collectAsStateWithLifecycle()
    val foldedIds by folded.collectAsStateWithLifecycle()
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    val snackbar = remember { SnackbarHostState() }
    NoticeEffect(model.notices, snackbar)
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { BarTitle(stringResource(R.string.groups_title), null) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.groups_back)) }
                },
                actions = {
                    IconButton(onClick = model::newGroupInBar, enabled = !blocks.isNullOrEmpty()) {
                        Icon(Icons.Rounded.CreateNewFolder, contentDescription = stringResource(R.string.groups_new_group))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                scrollBehavior = scroll,
            )
        },
        snackbarHost = { NoticeHost(snackbar, Modifier.navigationBarsPadding()) },
    ) { padding ->
        val current = blocks
        // Under the bar, not behind it: a block's header is pinned to the list's top.
        LazyColumn(
            Modifier.padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(
                start = readableInset(),
                end = readableInset(),
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp,
            ),
        ) {
            if (current == null) {
                item(key = "loading") { LoadingIndicator(Modifier.fillMaxWidth().padding(top = 48.dp)) }
            } else {
                val foldable = current.size >= 2
                for (block in current) {
                    val isOpen = !foldable || block.id !in foldedIds
                    stickyHeader(key = "head/${block.id.value}", contentType = "block header") { // not user text
                        BlockHeader(block, foldable, isOpen, readOnly = block.id in readOnlyIds, onFold = { folded.update { if (isOpen) it + block.id else it - block.id } })
                    }
                    if (isOpen) content(block, model.saving, announceSaving = model.dialog == null, onSearch, onHold = { model.openSheet(GroupsViewModel.GroupRef(block.id, it)) })
                    item(key = "end/${block.id.value}", contentType = "gap") { Spacer(Modifier.height(20.dp)) }
                }
                // not user text
                if (notOpen.isNotEmpty()) item(key = "not open") { NotOpenRow(notOpen, onLockScreen) }
            }
        }
    }
    val shown = blocks.orEmpty()
    model.sheet?.let { ref ->
        val block = shown.firstOrNull { it.id == ref.database }
        val row = block?.overview?.groups?.firstOrNull { it.id == ref.groupId }
        if (block != null && row != null) GroupSheet(block, row, model, several = shown.size >= 2)
    }
    model.dialog?.let { dialog ->
        shown.firstOrNull { it.id == dialog.database }?.let { NameDialog(it, dialog, model) }
    }
    model.moving?.let { ref ->
        val block = shown.firstOrNull { it.id == ref.database }
        val group = block?.vault?.groups?.firstOrNull { it.id == ref.groupId }
        if (block != null && group != null) {
            val tree = remember(block.vault) { GroupTree.of(block.vault) }
            val changes = remember(block.vault) { GroupChanges.of(block.vault) }
            GroupPickerSheet(
                tree = tree,
                chosen = null,
                made = "",
                onChoose = { model.move(it.groupId) },
                onDismiss = model::closeMove,
                title = GroupTexts.moveTitle(group).resolve(),
                subtitle = GroupTexts.moveSubtitle(group, tree.node(group.id)?.total ?: 0).resolve(),
                refusal = { target -> changes.moveRefusal(group.id, target)?.let { GroupTexts.moveRefusal(group, it) } },
            )
        }
    }
    if (model.choosingDatabase) {
        SaveIntoSheet(
            offered = databases(open.value),
            chosen = null,
            notOpen = notOpen.takeIf { it.isNotEmpty() }?.let { databases ->
                stringResource(R.string.groups_not_open_list, databases.joinToString(stringResource(R.string.list_separator)) { it.line.label.full })
            },
            onChoose = model::chooseDatabase,
            onDismiss = model::dismissDatabases,
            title = stringResource(R.string.groups_new_group),
            subtitle = stringResource(R.string.groups_new_group_where),
        )
    }
}

/**
 * A group's sheet, opened by a long press (G5; the AG3 mock-up): its path, its database and what it holds; New group
 * inside…, Rename…, Move to… and, when it is empty, Delete. Top level's has New group… only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupSheet(block: GroupsBlock, row: GroupsOverview.GroupRow, model: GroupsViewModel, several: Boolean) {
    val ref = GroupsViewModel.GroupRef(block.id, row.id)
    val tree = remember(block.vault) { GroupTree.of(block.vault) }
    val changes = remember(block.vault) { GroupChanges.of(block.vault) }
    val subgroups = tree.node(row.id)?.subgroups ?: 0
    ModalBottomSheet(onDismissRequest = model::closeSheet, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            Row(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (row.isRoot) Icons.Rounded.FolderOpen else Icons.Rounded.Folder,
                        contentDescription = null,
                        Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (row.isRoot) {
                        Text(GroupTexts.TOP_LEVEL.resolve(), style = MaterialTheme.typography.titleLarge)
                    } else {
                        PathText(row.path, style = MaterialTheme.typography.titleLarge, prefixStyle = SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (several) {
                            Box(Modifier.size(9.dp).clip(CircleShape).background(databaseColors(block.mark.colorIndex).dot))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            GroupTexts.sheetMeta(block.mark.label.full, row.total, row.here, subgroups).resolve(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            val actions = buildList {
                add(
                    SheetAction(
                        if (row.isRoot) R.string.groups_sheet_new_group else R.string.groups_sheet_new_group_inside,
                        null,
                        Icons.Rounded.CreateNewFolder,
                    ) { model.startNewGroup(ref) },
                )
                if (changes.canChange(row.id)) {
                    add(SheetAction(R.string.groups_sheet_rename, null, Icons.Rounded.Edit) { model.startRename(ref) })
                    add(SheetAction(R.string.groups_sheet_move, R.string.groups_sheet_move_detail, Icons.AutoMirrored.Rounded.DriveFileMove) { model.startMove(ref) })
                    if (changes.canDelete(row.id)) {
                        add(SheetAction(R.string.groups_sheet_delete, R.string.groups_sheet_delete_detail, Icons.Rounded.DeleteOutline, destructive = true) { model.delete(ref) })
                    }
                }
            }
            actions.forEachIndexed { index, action ->
                val color = if (action.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                Row(
                    Modifier
                        .padding(bottom = SegmentGap)
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clip(segmentShape(index, actions.size))
                        .background(MaterialTheme.extraColors.row)
                        .clickable(onClick = action.act)
                        .padding(start = 16.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(action.icon, contentDescription = null, Modifier.size(22.dp), tint = if (action.destructive) color else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(16.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(action.label), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = color)
                        action.detail?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}

private class SheetAction(@StringRes val label: Int, @StringRes val detail: Int?, val icon: ImageVector, val destructive: Boolean = false, val act: () -> Unit)

/**
 * The name's dialog (G5; the AG3 mock-up): Rename "Rename “Work”", New "New group in “Work”"; Save and Cancel. A name
 * refused, or a save that failed, is said under the field and the dialog stays; while it saves Save waits.
 */
@Composable
private fun NameDialog(block: GroupsBlock, dialog: GroupsViewModel.NameDialog, model: GroupsViewModel) {
    val groups = block.vault.groups
    val title = when (dialog) {
        is GroupsViewModel.NameDialog.Rename -> groups.firstOrNull { it.id == dialog.groupId }?.let(GroupTexts::renameTitle)
        is GroupsViewModel.NameDialog.New -> groups.firstOrNull { it.id == dialog.parentId }?.let(GroupTexts::newGroupTitle)
    } ?: return
    val start = (dialog as? GroupsViewModel.NameDialog.Rename)?.let { d -> groups.firstOrNull { it.id == d.groupId }?.name }.orEmpty()
    var field by remember(dialog) { mutableStateOf(TextFieldValue(start, TextRange(0, start.length))) }
    val focus = remember { FocusRequester() }
    val saving = model.saving != null
    val problem = model.dialogProblem
    AlertDialog(
        onDismissRequest = model::closeDialog,
        title = { Text(title.resolve()) },
        text = {
            NoLearningKeyboard {
                OutlinedTextField(
                    value = field,
                    onValueChange = {
                        if (it.text != field.text) model.edited()
                        field = it
                    },
                    modifier = Modifier.keepOutOfAutofill().fillMaxWidth().focusRequester(focus),
                    label = { Text(stringResource(R.string.groups_name_label)) },
                    singleLine = true,
                    isError = problem != null,
                    supportingText = problem?.let { { Text(it.resolve()) } },
                    enabled = !saving,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { model.submitName(field.text) }),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { model.submitName(field.text) }, enabled = !saving) {
                // Announced once (keething-spec app.accessibility AC6).
                Text(stringResource(if (saving) R.string.groups_saving else R.string.groups_save), if (saving) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier)
            }
        },
        dismissButton = { TextButton(onClick = model::closeDialog, enabled = !saving) { Text(stringResource(R.string.groups_cancel)) } },
    )
    LaunchedEffect(dialog) { focus.requestFocus() }
}

/** A block's header: its database's dot and label, how many entries it has; with two or more open, a tap folds it. */
@Composable
private fun BlockHeader(block: GroupsBlock, foldable: Boolean, isOpen: Boolean, readOnly: Boolean, onFold: () -> Unit) {
    val entries = pluralStringResource(R.plurals.groups_block_entries, block.entries, NumberFormat.getIntegerInstance().format(block.entries))
    val foldLabel = stringResource(if (isOpen) R.string.groups_fold else R.string.groups_unfold)
    val foldState = stringResource(if (isOpen) R.string.groups_unfolded else R.string.groups_folded)
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .heightIn(min = 52.dp)
            .then(
                if (foldable) {
                    Modifier
                        .clickable(onClickLabel = foldLabel, onClick = onFold)
                        .semantics { stateDescription = foldState }
                } else {
                    Modifier
                },
            )
            .semantics(mergeDescendants = true) { heading() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (readOnly) {
            ReadOnlyMark(databaseColors(block.mark.colorIndex).dot, 18.dp)
            Spacer(Modifier.width(6.dp))
        } else {
            Box(Modifier.size(12.dp).clip(CircleShape).background(databaseColors(block.mark.colorIndex).dot))
            Spacer(Modifier.width(12.dp))
        }
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            DatabaseLabelText(block.mark.label, MaterialTheme.typography.titleMedium, Modifier.weight(1f, fill = false), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text(entries, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        if (foldable) {
            val turn by animateFloatAsState(if (isOpen) 0f else -90f, label = "chevron")
            Icon(
                Icons.Rounded.ExpandMore,
                contentDescription = null,
                Modifier.size(22.dp).rotate(turn),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The window's databases not open, on one row under the blocks (G7; databases.not-open): "Not open:" and each one's dot
 * and label; a tap shows its lock screen, Back comes back here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotOpenRow(notOpen: List<NotOpenDatabase>, onLockScreen: (DatabaseId) -> Unit) {
    val single = notOpen.singleOrNull()
    val unlock = stringResource(R.string.groups_unlock)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(segmentShape(0, 1))
            .background(MaterialTheme.extraColors.row)
            .then(if (single != null) Modifier.clickable(onClickLabel = unlock) { onLockScreen(single.id) } else Modifier)
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlowRow(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.groups_not_open),
                Modifier.align(Alignment.CenterVertically).padding(end = 4.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            notOpen.forEach { database ->
                Row(
                    Modifier
                        .heightIn(min = if (single != null) 0.dp else 40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .then(if (single == null) Modifier.clickable(onClickLabel = unlock) { onLockScreen(database.id) } else Modifier)
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(databaseColors(database.line.colorIndex).dot))
                    Spacer(Modifier.width(8.dp))
                    DatabaseLabelText(database.line.label, MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                }
            }
        }
        if (single != null) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun LazyListScope.content(block: GroupsBlock, saving: GroupsViewModel.GroupRef?, announceSaving: Boolean, onSearch: (String) -> Unit, onHold: (groupId: String) -> Unit) {
    val overview = block.overview
    val id = block.id.value
    item(key = "groups/$id", contentType = "header") { Box(Modifier.padding(top = 6.dp)) { SectionTitle(stringResource(R.string.groups_section_groups)) } }
    itemsIndexed(overview.groups, key = { _, row -> "group/$id/${row.id}" }, contentType = { _, _ -> "row" }) { index, row ->
        OverviewRow(
            icon = if (row.isRoot) Icons.Rounded.FolderOpen else Icons.Rounded.Folder,
            title = if (row.isRoot) GroupTexts.TOP_LEVEL.resolve() else row.name,
            supporting = if (saving == GroupsViewModel.GroupRef(block.id, row.id)) stringResource(R.string.groups_saving) else groupCounts(row),
            // A dialog open says it on its button instead (the row is behind it).
            announced = announceSaving && saving == GroupsViewModel.GroupRef(block.id, row.id),
            count = row.total,
            indent = row.depth,
            shape = segmentShape(index, overview.groups.size),
            onClick = { onSearch(block.search(row.search)) },
            onLongClick = { onHold(row.id) },
        )
    }
    if (overview.recycleBin > 0) {
        item(key = "bin/$id", contentType = "row") {
            OverviewRow(
                icon = Icons.Rounded.Delete,
                title = stringResource(R.string.groups_recycle_bin),
                supporting = null,
                count = overview.recycleBin,
                shape = segmentShape(0, 1),
                onClick = { onSearch(block.search(GroupsOverview.RECYCLE_BIN_SEARCH)) },
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
    // Another app's forms for new entries (keething-spec groups.templates AC3): named and marked, nothing to open or change.
    overview.templates?.let { (name, count) ->
        item(key = "templates/$id", contentType = "row") {
            OverviewRow(
                icon = Icons.Rounded.Description,
                title = name,
                supporting = stringResource(R.string.groups_templates),
                count = count,
                shape = segmentShape(0, 1),
                onClick = null,
                modifier = Modifier.padding(top = if (overview.recycleBin > 0) 0.dp else 12.dp),
            )
        }
    }
    item(key = "gap/$id", contentType = "gap") { Spacer(Modifier.height(18.dp)) }
    item(key = "tags/$id", contentType = "header") { SectionTitle(stringResource(R.string.groups_section_tags)) }
    if (overview.tags.isEmpty()) {
        item(key = "no tags/$id") { // not user text
            Text(
                stringResource(R.string.groups_no_tags),
                Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    itemsIndexed(overview.tags, key = { _, row -> "tag/$id/${row.name}" }, contentType = { _, _ -> "row" }) { index, row ->
        OverviewRow(
            icon = Icons.Rounded.Sell,
            title = row.name,
            supporting = row.otherSpellings.takeIf { it.isNotEmpty() }?.let {
                stringResource(R.string.groups_tag_also, it.joinToString(stringResource(R.string.list_separator)))
            },
            count = row.count,
            shape = segmentShape(index, overview.tags.size),
            onClick = { onSearch(block.search(row.search)) },
        )
    }
}

/** The total is the row's number; under the name, how many of them are right in it when some are in its groups. */
@Composable
private fun groupCounts(row: GroupsOverview.GroupRow): String? = when {
    row.here == row.total -> null
    row.here == 0 -> stringResource(R.string.groups_none_here)
    else -> pluralStringResource(R.plurals.groups_here, row.here, NumberFormat.getIntegerInstance().format(row.here))
}

@Composable
private fun OverviewRow(
    icon: ImageVector,
    title: String,
    supporting: String?,
    count: Int,
    shape: RoundedCornerShape,
    /** Null: only said (the templates group). */
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    indent: Int = 0,
    /** A group's row: its sheet. */
    onLongClick: (() -> Unit)? = null,
    /** [supporting] says what is happening by itself ("Saving…"): announced once (keething-spec app.accessibility AC6). */
    announced: Boolean = false,
) {
    val entries = pluralStringResource(R.plurals.groups_entries, count, count)
    Row(
        modifier
            .padding(bottom = SegmentGap)
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(MaterialTheme.extraColors.row)
            .then(
                if (onClick == null) {
                    Modifier
                } else {
                    Modifier.combinedClickable(
                        onClick = onClick,
                        onClickLabel = stringResource(R.string.groups_show_entries),
                        onLongClick = onLongClick,
                        onLongClickLabel = if (onLongClick != null) stringResource(R.string.groups_change_group) else null,
                    )
                },
            )
            .padding(start = 16.dp + 20.dp * indent, end = 20.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (supporting != null) {
                Text(
                    supporting,
                    if (announced) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            NumberFormat.getIntegerInstance().format(count),
            Modifier.semantics { contentDescription = entries },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
