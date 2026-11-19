package dev.keething.android.databases

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.KeyOff
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.LockReset
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.QuestionMark
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Upgrade
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.keething.android.theme.extraColors
import dev.keething.android.ui.NoticeHost
import dev.keething.android.ui.SegmentGap
import dev.keething.android.ui.segmentShape
import dev.keething.vault.databases.DatabaseId
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.receiveAsFlow
import dev.keething.search.FilterCompletion
import dev.keething.search.FilterKind
import androidx.compose.ui.res.stringResource
import dev.keething.android.R
import dev.keething.android.ui.UiText
import dev.keething.android.ui.resolve
import dev.keething.android.ui.uiPlain

/**
 * A database not open, its mark in place of its dot (keething-spec databases.not-open AC8): its state's icon in the
 * database's [color]; a spinner while it opens. The two drawn of two symbols cut them out of [behind], what lies under.
 */
@Composable
internal fun NotOpenMark(state: NotOpenState, color: Color, size: Dp, behind: Color = MaterialTheme.extraColors.row) {
    val description = state.word.resolve()
    when (state.glyph) {
        NotOpenState.Glyph.SPINNER -> Box(Modifier.size(size).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(size * 0.8f), color = color, strokeWidth = 2.dp)
        }
        NotOpenState.Glyph.FOLDER_QUESTION -> Box(Modifier.size(size).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Folder, contentDescription = null, Modifier.size(size), tint = color)
            Icon(Icons.Rounded.QuestionMark, contentDescription = null, Modifier.padding(top = size * 0.12f).size(size * 0.5f), tint = behind)
        }
        NotOpenState.Glyph.LOCK_WARNING -> Box(Modifier.size(size).semantics { contentDescription = description }) {
            Icon(Icons.Rounded.Lock, contentDescription = null, Modifier.size(size), tint = color)
            Box(
                Modifier.align(Alignment.BottomEnd).offset(x = size * 0.2f, y = size * 0.14f).size(size * 0.68f).clip(CircleShape).background(behind),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Error, contentDescription = null, Modifier.size(size * 0.58f), tint = color)
            }
        }
        else -> Icon(
            when (state.glyph) {
                NotOpenState.Glyph.LOCK -> Icons.Rounded.Lock
                NotOpenState.Glyph.FINGERPRINT -> Icons.Rounded.Fingerprint
                NotOpenState.Glyph.FOLDER_OFF -> Icons.Rounded.FolderOff
                NotOpenState.Glyph.FOLDER_NEW -> Icons.Rounded.CreateNewFolder
                NotOpenState.Glyph.KEY_OFF -> Icons.Rounded.KeyOff
                NotOpenState.Glyph.WARNING -> Icons.Rounded.WarningAmber
                NotOpenState.Glyph.LOCK_RESET -> Icons.Rounded.LockReset
                else -> Icons.Rounded.SkipNext
            },
            contentDescription = description,
            Modifier.size(size),
            tint = color,
        )
    }
}

/**
 * The databases that need attention, on one line under the list's header with no title (keething-spec
 * databases.not-open AC1, AC2, databases.read-only-mark AC4; the owner, 2026-11-07): those not open, then the open ones
 * that are read-only; the chips scroll sideways and fade at the end. A database not open: a tap shows its lock screen, a
 * long press its sheet, and while a search is typed it is read aloud as not searched. A read-only one: a tap or a long
 * press its sheet.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NotOpenLine(
    notOpen: List<NotOpenDatabase>,
    readOnly: List<ReadOnlyDatabase>,
    typing: Boolean,
    onTap: (DatabaseId) -> Unit,
    onLongPress: (DatabaseId) -> Unit,
    onReadOnly: (DatabaseId) -> Unit,
) {
    if (notOpen.isEmpty() && readOnly.isEmpty()) return
    val surface = MaterialTheme.colorScheme.surface
    Box(Modifier.fillMaxWidth().background(surface).padding(bottom = 10.dp, top = 2.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(start = 24.dp, end = 32.dp)) {
            items(notOpen, key = { it.id.value }) { database ->
                val say = database.say.resolve()
                val readAloud = NotOpenDatabases.readAloud(typing)?.resolve()
                AttentionChip(
                    database.line,
                    Modifier
                        .combinedClickable(
                            onClickLabel = stringResource(R.string.databases_unlock),
                            onLongClickLabel = stringResource(R.string.databases_more),
                            onLongClick = { onLongPress(database.id) },
                            onClick = { onTap(database.id) },
                        )
                        .semantics {
                            readAloud?.let { stateDescription = it }
                            customActions = listOf(CustomAccessibilityAction(say) { true })
                        },
                ) { NotOpenMark(database.state, databaseColors(database.line.colorIndex).dot, 16.dp) }
            }
            items(readOnly, key = { "r/${it.id.value}" }) { database ->
                val say = database.say.resolve()
                val more = stringResource(R.string.databases_more)
                AttentionChip(
                    database.line,
                    Modifier
                        .combinedClickable(
                            onClickLabel = more,
                            onLongClickLabel = more,
                            onLongClick = { onReadOnly(database.id) },
                            onClick = { onReadOnly(database.id) },
                        )
                        .semantics { customActions = listOf(CustomAccessibilityAction(say) { true }) },
                ) { ReadOnlyMark(databaseColors(database.line.colorIndex).dot, 16.dp) }
            }
        }
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .width(40.dp)
                .height(36.dp)
                .background(Brush.horizontalGradient(listOf(surface.copy(alpha = 0f), surface))),
        )
    }
}

/** A chip of [NotOpenLine]: its [mark] and the database's name. */
@Composable
private fun AttentionChip(line: DatabaseLine, modifier: Modifier, mark: @Composable () -> Unit) {
    Row(
        Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.extraColors.row)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            .then(modifier)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        mark()
        DatabaseLabelText(line.label, MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

/**
 * An open database that is read-only, its sheet (keething-spec databases.read-only-mark AC4): its tile, name and folder,
 * why, then Lock the others (when they are what makes it so: at once, nothing asked; it becomes changeable once they
 * lock), Update to KDBX 4… (KDBX 3.1: the question Edit asks), Show only this, Lock it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReadOnlySheet(
    database: ReadOnlyDatabase,
    showOnly: String,
    onLockOthers: () -> Unit,
    onUpdate: () -> Unit,
    onShowOnly: () -> Unit,
    onLock: () -> Unit,
    onDismiss: () -> Unit,
) {
    val line = database.line
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DatabaseSheetHeader(line)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.extraColors.row).padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ReadOnlyMark(databaseColors(line.colorIndex).dot, 20.dp)
                Text(database.say.resolve(), style = MaterialTheme.typography.bodyMedium)
            }
            if (database.besideOthers) SheetRow(Icons.Rounded.Lock, stringResource(R.string.databases_lock_others), detail = stringResource(R.string.databases_lock_others_detail), onClick = onLockOthers)
            if (database.reason == ReadOnlyReason.KDBX3) SheetRow(Icons.Rounded.Upgrade, stringResource(R.string.databases_update_kdbx4), onClick = onUpdate)
            SheetRow(Icons.Rounded.FilterAlt, stringResource(R.string.databases_show_only), detail = stringResource(R.string.databases_show_only_detail, showOnly), onClick = onShowOnly)
            SheetRow(Icons.Rounded.Lock, stringResource(R.string.databases_lock_database, line.label.full), onClick = onLock)
        }
    }
}

/**
 * A database not open, its sheet (keething-spec databases.not-open AC5): its tile, name and folder, its whole say, then
 * Unlock… (not when its file is not found or cannot be read), Locate… (not found, no access) or Choose its folder… (kept
 * by its path), Choose key file… (it has one), Close
 * (not the window's last).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotOpenSheet(
    database: NotOpenDatabase,
    canClose: Boolean,
    onUnlock: () -> Unit,
    onLocate: () -> Unit,
    onChooseKeyFile: () -> Unit,
    onClose: () -> Unit,
    onDismiss: () -> Unit,
) {
    val line = database.line
    val state = database.state
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DatabaseSheetHeader(line)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.extraColors.row).padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                NotOpenMark(state, databaseColors(line.colorIndex).dot, 20.dp)
                Text(database.say.resolve(), style = MaterialTheme.typography.bodyMedium)
            }
            SheetRow(Icons.Rounded.LockOpen, stringResource(R.string.databases_unlock_button), enabled = database.offersUnlock, onClick = onUnlock)
            if (state is NotOpenState.NotFound) {
                SheetRow(Icons.Rounded.Search, stringResource(if (state.reach == Reach.NEEDS_FOLDER) R.string.databases_lock_choose_folder else R.string.databases_lock_locate), onClick = onLocate)
            }
            if (line.keyFile != null) SheetRow(Icons.Rounded.Key, stringResource(R.string.databases_choose_key_file), onClick = onChooseKeyFile)
            SheetRow(
                Icons.Rounded.Close,
                stringResource(R.string.databases_close),
                detail = stringResource(if (canClose) R.string.databases_close_detail else R.string.databases_last_one_stays),
                enabled = canClose,
                onClick = onClose,
            )
        }
    }
}

/** A database's sheet's first row: its tile, its name and its folder. */
@Composable
internal fun DatabaseSheetHeader(line: DatabaseLine) {
    Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        DatabaseTile(line.initial, line.colorIndex)
        Column(Modifier.weight(1f)) {
            DatabaseLabelText(line.label, MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(line.folder, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun SheetRow(icon: ImageVector, text: String, detail: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else color)
        Column {
            Text(text, style = MaterialTheme.typography.bodyLarge, color = color)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** An open database's sheet in ⋮ › Databases: Show only this (types `!Label `), Lock it, Close. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OpenDatabaseSheet(
    line: DatabaseLine,
    showOnly: String,
    canClose: Boolean,
    onShowOnly: () -> Unit,
    onLock: () -> Unit,
    onClose: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DatabaseTile(line.initial, line.colorIndex)
                Column(Modifier.weight(1f)) {
                    DatabaseLabelText(line.label, MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("${line.folder}/${line.fileName}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                }
            }
            SheetRow(Icons.Rounded.FilterAlt, stringResource(R.string.databases_show_only), detail = stringResource(R.string.databases_show_only_detail, showOnly), onClick = onShowOnly)
            SheetRow(Icons.Rounded.Lock, stringResource(R.string.databases_lock_database, line.label.full), onClick = onLock)
            SheetRow(Icons.Rounded.Close, stringResource(R.string.databases_close), detail = if (canClose) null else stringResource(R.string.databases_last_one_stays), enabled = canClose, onClick = onClose)
        }
    }
}

/**
 * ⋮ › Databases (the agreed mock-up; keething-spec databases.several-open AC4): Open (a tap: its sheet), Not open (its
 * state and whole say; a tap: its lock screen), Recent (closed ones, the last opened first; a tap puts it back and shows
 * its lock screen), New database… and Open database…. A row of the first two swiped left closes it, with Undo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DatabasesScreen(
    overview: DatabasesOverview?,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onOpenSheet: (DatabaseId) -> Unit,
    onLockScreen: (DatabaseId) -> Unit,
    onPutBack: (DatabaseId) -> Unit,
    onClose: (DatabaseId) -> Unit,
    onNew: () -> Unit,
    onOpenOther: () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.databases_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.databases_back)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        snackbarHost = { NoticeHost(snackbar) },
    ) { padding ->
        val sections = overview ?: return@Scaffold
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 24.dp)) {
            if (sections.open.isNotEmpty()) {
                item(key = "h/open") { SectionTitle(stringResource(R.string.databases_section_open)) }
                itemsIndexed(sections.open, key = { _, it -> "o/${it.id.value}" }) { index, line ->
                    SwipeToClose(sections.canClose, onClose = { onClose(line.id) }, shape = segmentShape(index, sections.open.size, outer = 22.dp)) {
                        val readOnly = sections.readOnly(line.id)
                        DatabaseRow(line, detail = readOnly?.say ?: uiPlain(line.folder), readOnly = readOnly != null, chevron = true, onClick = { onOpenSheet(line.id) })
                    }
                }
            }
            if (sections.notOpen.isNotEmpty()) {
                item(key = "h/not open") { SectionTitle(stringResource(R.string.databases_section_not_open)) } // not user text: the key
                itemsIndexed(sections.notOpen, key = { _, it -> "n/${it.id.value}" }) { index, database ->
                    SwipeToClose(sections.canClose, onClose = { onClose(database.id) }, shape = segmentShape(index, sections.notOpen.size, outer = 22.dp)) {
                        DatabaseRow(database.line, detail = database.say, state = database.state, onClick = { onLockScreen(database.id) })
                    }
                }
            }
            if (sections.recent.isNotEmpty()) {
                item(key = "h/recent") { SectionTitle(stringResource(R.string.databases_section_recent)) }
                itemsIndexed(sections.recent, key = { _, it -> "r/${it.id.value}" }) { index, line ->
                    Box(Modifier.padding(bottom = SegmentGap).clip(segmentShape(index, sections.recent.size, outer = 22.dp))) {
                        DatabaseRow(line, detail = line.sheetDetail, onClick = { onPutBack(line.id) })
                    }
                }
            }
            item(key = "actions") {
                Column(Modifier.padding(top = 16.dp)) {
                    SheetRow(Icons.Rounded.Add, stringResource(R.string.databases_new_database_button), onClick = onNew)
                    SheetRow(Icons.Rounded.FolderOpen, stringResource(R.string.databases_open_database_button), onClick = onOpenOther)
                    Text(
                        stringResource(R.string.databases_swipe_hint),
                        Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        Modifier.padding(start = 8.dp, top = 16.dp, bottom = 8.dp).semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun DatabaseRow(line: DatabaseLine, detail: UiText, state: NotOpenState? = null, readOnly: Boolean = false, chevron: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .background(MaterialTheme.extraColors.row)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            when {
                state != null -> NotOpenMark(state, databaseColors(line.colorIndex).dot, 20.dp)
                readOnly -> ReadOnlyMark(databaseColors(line.colorIndex).dot, 20.dp)
                else -> DatabaseDot(line.colorIndex)
            }
        }
        Column(Modifier.weight(1f)) {
            DatabaseLabelText(line.label, MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(detail.resolve(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (chevron) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * A row that closes its database when swiped left past a threshold (the mock-up: "Close" under it, filled once far
 * enough); the window's last says "The last one stays" and springs back.
 */
@Composable
private fun SwipeToClose(canClose: Boolean, onClose: () -> Unit, shape: androidx.compose.ui.graphics.Shape, content: @Composable () -> Unit) {
    val threshold = with(LocalDensity.current) { 88.dp.toPx() }
    val limit = with(LocalDensity.current) { 140.dp.toPx() }
    // The gesture outlives recompositions: it calls the latest handler (the current database may have changed since).
    val close by androidx.compose.runtime.rememberUpdatedState(onClose)
    var drag by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val shown by animateFloatAsState(if (dragging) drag else 0f, label = "swipe")
    val closeLabel = stringResource(R.string.databases_close)
    val far = drag <= -threshold
    Box(Modifier.padding(bottom = SegmentGap).clip(shape)) {
        Row(
            Modifier
                .matchParentSize()
                .background(
                    when {
                        !canClose -> MaterialTheme.colorScheme.surfaceContainerHighest
                        far -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.primaryContainer
                    },
                )
                .padding(end = 20.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val color = when {
                !canClose -> MaterialTheme.colorScheme.onSurfaceVariant
                far -> MaterialTheme.colorScheme.onPrimary
                else -> MaterialTheme.colorScheme.onPrimaryContainer
            }
            Text(stringResource(if (canClose) R.string.databases_close else R.string.databases_last_one_stays), color = color, fontWeight = if (far) FontWeight.Bold else FontWeight.Medium)
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Rounded.Close, contentDescription = null, tint = color)
        }
        Box(
            Modifier
                .offset { IntOffset((if (dragging) drag else shown).roundToInt(), 0) }
                .pointerInput(canClose) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = {
                            val closing = canClose && drag <= -threshold
                            dragging = false
                            drag = 0f
                            if (closing) close()
                        },
                        onDragCancel = {
                            dragging = false
                            drag = 0f
                        },
                    ) { _, amount ->
                        drag = (drag + amount).coerceIn(-limit, 0f)
                    }
                }
                .semantics { if (canClose) customActions = listOf(CustomAccessibilityAction(closeLabel) { close(); true }) },
        ) { content() }
    }
}

/** ⋮ › Databases with its sheets, its notices and Open database… (a folder, then its files). */
@Composable
internal fun WindowDatabasesScreen(
    container: dev.keething.android.AppContainer,
    overview: DatabasesOverview?,
    onBack: () -> Unit,
    onLockScreen: (DatabaseId) -> Unit,
    onClose: (DatabaseId) -> Unit,
    onShowOnly: (label: String) -> Unit,
    /** A file opened from here: it is current, its lock screen shows with Back. */
    onOpened: (DatabaseId) -> Unit,
) {
    val databases = container.sessions.databases
    val snackbar = remember { SnackbarHostState() }
    dev.keething.android.ui.NoticeEffect(remember { container.windowNotices.receiveAsFlow() }, snackbar)
    var sheet by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val files = rememberFileActions(container, onOpened = onOpened)
    overview?.open?.firstOrNull { it.id.value == sheet }?.let { line ->
        val showOnly = FilterCompletion.token(FilterKind.DATABASE, line.label.full)
        OpenDatabaseSheet(
            line = line,
            showOnly = showOnly,
            canClose = overview.canClose,
            onShowOnly = {
                sheet = null
                onShowOnly(line.label.full)
            },
            onLock = {
                sheet = null
                databases.lock(line.id)
            },
            onClose = {
                sheet = null
                onClose(line.id)
            },
            onDismiss = { sheet = null },
        )
    }
    DatabasesScreen(
        overview = overview,
        snackbar = snackbar,
        onBack = onBack,
        onOpenSheet = { sheet = it.value },
        onLockScreen = onLockScreen,
        onPutBack = { id ->
            databases.putBack(id)
            onLockScreen(id)
        },
        onClose = onClose,
        onNew = container.newDatabases::open,
        onOpenOther = files.open,
    )
}
