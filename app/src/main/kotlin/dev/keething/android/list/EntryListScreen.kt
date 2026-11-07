package dev.keething.android.list

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import dev.keething.android.recyclebin.DeleteForGoodDialog
import dev.keething.android.ui.NoteTile
import androidx.compose.ui.draw.rotate
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Key
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.keething.android.AppContainer
import dev.keething.android.generator.GeneratorUse
import dev.keething.android.generator.PasswordGeneratorSheet
import dev.keething.android.navigation.inTwoPanes
import dev.keething.android.navigation.sharedIcon
import dev.keething.android.navigation.sharedTitle
import dev.keething.android.databases.DatabaseMark
import dev.keething.android.databases.databaseColors
import dev.keething.android.theme.MonospaceFamily
import dev.keething.android.theme.extraColors
import dev.keething.android.editor.NewEntryDatabases
import dev.keething.android.ui.CodeRing
import dev.keething.android.ui.CodesIcon
import dev.keething.android.ui.CustomIconImages
import dev.keething.android.ui.EntryMark
import dev.keething.android.ui.FitText
import dev.keething.android.ui.NoLearningKeyboard
import dev.keething.android.ui.PathText
import androidx.compose.ui.text.AnnotatedString
import dev.keething.android.ui.Notice
import dev.keething.android.databases.NotOpenDatabase
import dev.keething.android.databases.NotOpenLine
import dev.keething.android.databases.NotOpenSheet
import dev.keething.android.databases.rememberFileActions
import dev.keething.android.databases.Reach
import dev.keething.android.ui.keepOutOfAutofill
import dev.keething.android.unlock.FingerprintQuestion
import dev.keething.vault.biometric.FingerprintAnswer
import dev.keething.vault.databases.DatabaseId
import androidx.compose.material.icons.rounded.Storage
import dev.keething.android.ui.NoticeEffect
import dev.keething.android.ui.NoticeHost
import dev.keething.android.ui.SegmentGap
import dev.keething.android.ui.rememberSecondsNow
import dev.keething.android.ui.segmentShape
import dev.keething.card.EntryFace
import dev.keething.card.ExpiryHint
import dev.keething.card.TotpDisplay
import dev.keething.domain.totp.TotpConfig
import dev.keething.search.EntrySearch
import java.text.NumberFormat
import java.time.Instant
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * The list (SPEC §6.2): nothing typed shows the recently used entries, then all by title; the search field
 * is at the bottom, in thumb reach above the keyboard, with the filters as chips over it while it has the focus.
 * A row opens its card; swiped right it copies the password, left the username; a long press offers the quick
 * actions; a row with a one-time code shows it live, as a button that copies it. [codesOnly]: the Codes view instead,
 * a tile per one-time code ([CodeTiles], keething-spec app.codes-view); Back or the arrow leaves it for the whole list.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EntryListScreen(
    container: AppContainer,
    query: TextFieldState,
    codesOnly: Boolean,
    selectedId: String?,
    onOpen: (id: String, origin: String) -> Unit,
    onEdit: (id: String) -> Unit,
    onSettings: () -> Unit,
    onGroups: () -> Unit,
    /** The header's Codes button: the Codes view. */
    onCodes: () -> Unit,
    onLeaveCodes: () -> Unit,
    /** The `+`'s choice: a new entry, a new note, a new note from the clipboard (SPEC §6.2, §6.4). */
    onNew: (NewChoice) -> Unit,
    onSharing: () -> Unit,
    /** The window's databases not open (keething-spec databases.not-open), and how many are open. */
    notOpen: List<NotOpenDatabase> = emptyList(),
    openCount: Int = 1,
    canClose: Boolean = false,
    /** A database's lock screen, with Back (a chip's tap, Unlock… in its sheet). */
    onLockScreen: (DatabaseId) -> Unit = {},
    onCloseDatabase: (DatabaseId) -> Unit = {},
    onDatabases: () -> Unit = {},
) {
    val codes by rememberUpdatedState(codesOnly)
    // Beside the card (two panes), the open entry's rows are marked.
    val selected = selectedId?.takeIf { inTwoPanes() }
    val holdRecent by rememberUpdatedState(selected != null)
    val requests by container.shareRequests.book.collectAsStateWithLifecycle()
    val model = viewModel {
        val sessions = container.sessions
        EntryListViewModel(
            sessions.open, sessions.state, sessions::entry, container.recents, container.toldUnlocks, container.recycleBin,
            container.clipboard, query,
            codesOnly = { codes }, besideCard = { holdRecent }, databases = sessions.list.state,
            newEntryMade = { container.settings.lastNewEntryDatabase = it },
        )
    }
    val state by model.state.collectAsStateWithLifecycle()
    val chips by model.chips.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    // The screen's own problems (no app opens a link): shown as the model's are.
    val problems = remember { Channel<Notice>(Channel.BUFFERED) }
    NoticeEffect(model.notices, snackbar)
    NoticeEffect(container.recycleBin.notices, snackbar)
    NoticeEffect(remember { problems.receiveAsFlow() }, snackbar)
    NoticeEffect(remember { container.windowNotices.receiveAsFlow() }, snackbar)
    // A chip's long press: its sheet (keething-spec databases.not-open AC5).
    var sheetOf by rememberSaveable { mutableStateOf<String?>(null) }
    val files = rememberFileActions(container, onProblem = { problem -> problem?.let { container.windowNotices.trySend(Notice(it, isProblem = true)) } })
    notOpen.firstOrNull { it.id.value == sheetOf }?.let { database ->
        NotOpenSheet(
            database,
            canClose = canClose,
            onUnlock = {
                sheetOf = null
                onLockScreen(database.id)
            },
            onLocate = {
                sheetOf = null
                files.locate(database.id, database.line.reach == Reach.NO_ACCESS)
            },
            onChooseKeyFile = {
                sheetOf = null
                files.chooseKeyFile(database.id)
            },
            onClose = {
                sheetOf = null
                onCloseDatabase(database.id)
            },
            onDismiss = { sheetOf = null },
        )
    }
    // While the keyboard is open, Back closes it first, as everywhere on Android; then it clears the search.
    BackHandler(enabled = (query.text.isNotEmpty() || codesOnly) && !WindowInsets.isImeVisible) {
        if (query.text.isNotEmpty()) query.clearText() else onLeaveCodes()
    }
    val now = rememberSecondsNow()
    // A password without an entry; the sheet's password goes with it.
    var generating by rememberSaveable { mutableStateOf(false) }
    if (generating) {
        val generator = remember { container.passwordGenerator() }
        PasswordGeneratorSheet(
            generator,
            GeneratorUse.Copy { password ->
                model.copyGenerated(password)
                generating = false
            },
            onDismiss = { generating = false },
        )
    }

    // A typed unlock opened a database whose fingerprint was never chosen: asked once over the list (keething-spec
    // unlock.biometric AC4).
    val question by container.sessions.fingerprintQuestion.collectAsStateWithLifecycle()
    question?.let { database ->
        val asked by database.biometric.state.collectAsStateWithLifecycle()
        val name = container.sessions.list.record(database.id)?.displayName ?: database.location.fileName
        FingerprintQuestion(name, passwordless = asked.questionNothingTyped) { use ->
            container.sessions.answerFingerprint(database, use) { answer ->
                val notice = when (answer) {
                    is FingerprintAnswer.Failed -> Notice("The fingerprint could not be set up: ${answer.reason}.", isProblem = true)
                    FingerprintAnswer.TurnedOn -> Notice("Your fingerprint opens “$name” from now on.")
                    FingerprintAnswer.Declined, FingerprintAnswer.Nothing -> null
                }
                notice?.let(container.windowNotices::trySend)
            }
        }
    }

    // The quick actions of a long-pressed row, with the section it was in (Remove from Recent is only for a recent row).
    var quick by remember { mutableStateOf<Pair<QuickActions, ListSection.Kind>?>(null) }
    // A row in the bin whose Delete for good… was tapped: the dialog asks first (its id and title).
    var deletingForGood by remember { mutableStateOf<Pair<String, String>?>(null) }
    deletingForGood?.let { (id, title) ->
        DeleteForGoodDialog(
            title = title,
            onDelete = {
                deletingForGood = null
                model.deleteForGood(id)
            },
            onDismiss = { deletingForGood = null },
        )
    }
    val context = LocalContext.current
    quick?.let { (actions, kind) ->
        QuickActionsSheet(
            actions = actions,
            fromRecent = kind == ListSection.Kind.RECENT,
            icons = state.icons,
            now = now,
            handler = object : QuickActionHandler {
                override fun copyUsername(id: String) = model.copyUsername(id)
                override fun copyPassword(id: String) = model.copyPassword(id)
                override fun copyCode(id: String) = model.copyCode(id)
                override fun edit(id: String) = onEdit(id)
                override fun removeFromRecent(id: String) = model.removeFromRecent(id)
                override fun moveToRecycleBin(id: String) = model.moveToRecycleBin(id)
                override fun restore(id: String) = model.restore(id)
                override fun deleteForGood(id: String) {
                    deletingForGood = id to actions.row.title
                }
                override fun openSite(id: String, url: String) {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
                        model.opened(id)
                    } catch (_: ActivityNotFoundException) {
                        problems.trySend(Notice("No app opens this link", isProblem = true))
                    }
                }
            },
            onDismiss = { quick = null },
        )
    }

    // A tile's ⋯ in the Codes view, and what it opens (keething-spec app.codes-view).
    var codeSheet by remember { mutableStateOf<CodeTile?>(null) }
    var editing by remember { mutableStateOf<CodeTile?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    codeSheet?.let { tile ->
        CodeSheet(
            tile, state.icons, now,
            deletesEntry = remember(tile) { model.deletesEntry(tile) },
            onCopy = { model.copyCode(tile) },
            onOpenEntry = { onOpen(tile.entryId, CODES_ORIGIN) },
            onEdit = { editing = tile },
            onDelete = { model.deleteCode(tile) },
            onDismiss = { codeSheet = null },
        )
    }
    editing?.let { tile ->
        EditCodeSheet(tile, now, onSave = { config, name -> model.editCode(tile, config, name) }, onDismiss = { editing = null })
    }
    val openDatabases by container.sessions.open.collectAsStateWithLifecycle()
    val places = remember(openDatabases) { container.sessions.newEntryDatabases(openDatabases).filter { it.takesEntries } }
    val addCode: () -> Unit = {
        // Only databases viewed only are open: a code has nowhere to go (as a new entry, KeethingApp's onNew).
        if (places.isNotEmpty()) adding = true else openDatabases.firstOrNull()?.let { container.sessions.kdbx3.ask(it.database) }
    }
    if (adding && places.isNotEmpty()) {
        val start = remember(places) { NewEntryDatabases.start(places, query.text.toString(), null, container.settings.lastNewEntryDatabase) }
        AddCodeSheet(
            places, start, now,
            onAdd = { place, into, title, username, config ->
                openDatabases.firstOrNull { it.database.id == place.id }?.let { model.addCode(it.database, into, title, username, config) }
            },
            onDismiss = { adding = false },
        )
    }

    // Landscape on a phone: one row fits, so the header is compact from the start.
    val compactHeader = LocalConfiguration.current.screenHeightDp < 480
    // The large header collapses into a compact bar while the list scrolls, and is opaque once anything is under it.
    val scroll = if (compactHeader) TopAppBarDefaults.pinnedScrollBehavior() else TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var searchFocused by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    // The keyboard takes half the screen: the large header makes room as the search field takes the focus, and comes
    // back when it lets the focus go with the list at its top.
    LaunchedEffect(searchFocused) {
        if (compactHeader) return@LaunchedEffect
        val atTop = if (codesOnly) gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0
        else listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        if (!searchFocused && !atTop) return@LaunchedEffect
        val bar = scroll.state
        animate(bar.heightOffset, if (searchFocused) bar.heightOffsetLimit else 0f) { value, _ -> bar.heightOffset = value }
    }
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Column {
                Header(
                    state, scroll, codesOnly, compactHeader, onLock = container.sessions::lock, onGenerator = { generating = true }, onGroups = onGroups,
                    onSettings = onSettings, onCodes = onCodes, onLeaveCodes = onLeaveCodes, onSharing = onSharing, toAnswer = requests?.theirs?.size ?: 0,
                    onDatabases = onDatabases, openCount = openCount,
                )
                // Pinned under the header: a search never silently misses a locked database (keething-spec databases.not-open).
                NotOpenLine(notOpen, typing = query.text.isNotEmpty(), onTap = onLockScreen, onLongPress = { sheetOf = it.value })
            }
        },
        bottomBar = {
            SearchField(
                query,
                placeholder = if (codesOnly) "Search codes" else SEARCH_HINT,
                label = if (codesOnly) "Search codes" else "Search entries",
                onFocusChange = { searchFocused = it },
                above = { if (searchFocused) SearchChipRow(chips) { query.setTextAndPlaceCursorAtEnd(it) } },
            ) {
                if (codesOnly) AddCodeButton(addCode) else NewButton(onNew)
            }
        },
        snackbarHost = { NoticeHost(snackbar) },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (codesOnly) {
                val text = query.text.toString()
                CodeTiles(
                    state, maxWidth, padding, gridState, now, text, searched = !EntrySearch.isEmpty(text),
                    onCopy = model::copyCode,
                    onMore = { codeSheet = it },
                )
                return@BoxWithConstraints
            }
            val codeUnder = codeGoesUnder(maxWidth)
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 16.dp),
            ) {
                for (banner in state.banners) {
                    item(key = banner.key) { Banner(title = banner.title, text = banner.text, onDismiss = banner.onDismiss) }
                }
                if (state.preparing) {
                    item(key = "preparing") {
                        Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
                    }
                }
                for (section in state.sections) {
                    item(key = "header/${section.kind}", contentType = "header") {
                        SectionTitle(section.title())
                    }
                    itemsIndexed(section.rows, key = { _, row -> "${section.kind}/${row.id}" }, contentType = { _, _ -> "row" }) { index, row ->
                        val shape = segmentShape(index, section.rows.size)
                        // A deleted row fades out and the rows below close up (SPEC §6.2).
                        SwipeToCopy(
                            shape,
                            hasPassword = row.hasPassword,
                            hasUsername = row.hasUsername,
                            onCopyPassword = { model.copyPassword(row.id) },
                            onCopyUsername = { model.copyUsername(row.id) },
                            modifier = Modifier.animateItem().padding(bottom = SegmentGap),
                        ) {
                            EntryRowView(
                                row = row,
                                shape = shape,
                                sharedKey = "${section.kind}/${row.id}",
                                icons = state.icons,
                                now = now,
                                onOpen = { onOpen(row.id, section.kind.name) },
                                selected = row.id == selected,
                                onLongPress = { model.quickActions(row)?.let { quick = it to section.kind } },
                                onCopyCode = { model.copyCode(row.id) },
                                codeUnder = codeUnder,
                            )
                        }
                    }
                    item(key = "gap/${section.kind}", contentType = "gap") { Spacer(Modifier.height(18.dp)) }
                }
                // Bare signs alone (`@`, `!`) list nothing and say nothing: the chips offer what they can be.
                val searched = !EntrySearch.isEmpty(query.text.toString())
                if (!state.preparing && state.sections.isEmpty() && searched) {
                    item(key = "nothing") {
                        val emptyBin = EntrySearch.recycleBinQuery(query.text.toString())?.isBlank() == true
                        Text(
                            when {
                                emptyBin -> "The recycle bin is empty."
                                else -> "Nothing matches “${query.text}”."
                            },
                            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

internal fun ListSection.title(): String = when (kind) {
    ListSection.Kind.SUGGESTED -> "Suggested"
    ListSection.Kind.RECENT -> "Recent"
    ListSection.Kind.ALL -> "All entries"
    ListSection.Kind.RESULTS -> if (rows.size == 1) "1 entry" else "${NumberFormat.getIntegerInstance().format(rows.size)} entries"
    ListSection.Kind.RECYCLE_BIN -> "Recycle bin"
}

/** The card's origin for an entry opened from the Codes view: no row of the list to grow from. */
private const val CODES_ORIGIN = "codes"

/** The search field's hint; the filters are chips over the keyboard (SPEC §6.2). */
internal const val SEARCH_HINT = "Search"

/**
 * Whether a row's code goes under its title rather than beside it: when the pill beside it would leave the title
 * less than about seven and a half characters' room ("Apfel …" at a large text size). [listWidth]: the list's.
 */
@Composable
private fun codeGoesUnder(listWidth: Dp): Boolean {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val codeStyle = codeTextStyle()
    val title = MaterialTheme.typography.bodyLarge.fontSize
    return remember(listWidth, density, codeStyle, title) {
        with(density) {
            val code = measurer.measure("000 000", codeStyle).size.width.toDp()
            // The pill: its paddings, gap and ring, and its margin; the row: the list's and its own paddings, the icon and its gap.
            val pill = code + 12.dp + 8.dp + 6.dp + 16.dp + 8.dp
            val room = listWidth - 32.dp - 16.dp - 44.dp - 16.dp - 8.dp - pill
            room < title.toDp() * 7.5f
        }
    }
}

@Composable
private fun codeTextStyle() = MaterialTheme.typography.bodyLarge.copy(fontFamily = MonospaceFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)

@Composable
private fun Header(
    state: ListUiState,
    scroll: TopAppBarScrollBehavior,
    codesOnly: Boolean,
    compact: Boolean,
    onLock: () -> Unit,
    onGenerator: () -> Unit,
    onGroups: () -> Unit,
    onSettings: () -> Unit,
    onCodes: () -> Unit,
    onLeaveCodes: () -> Unit,
    onSharing: () -> Unit,
    /** Requests waiting for an answer (SPEC §10): a dot on Sharing. */
    toAnswer: Int,
    onDatabases: () -> Unit,
    openCount: Int,
) {
    val count = if (codesOnly) {
        if (state.preparing) "" else " · " + state.codes.size.let { if (it == 1) "1 code" else "${NumberFormat.getIntegerInstance().format(it)} codes" }
    } else {
        state.entryCount?.let { " · ${NumberFormat.getIntegerInstance().format(it)} entries" }.orEmpty()
    }
    val title = @Composable { Text(if (codesOnly) "Codes" else "Keething", maxLines = 1, overflow = TextOverflow.Ellipsis) }
    val subtitle = @Composable { FitText(state.databaseName + count) }
    val navigationIcon = @Composable {
        if (codesOnly) {
            IconButton(onClick = onLeaveCodes) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "All entries") }
        }
    }
    // Lock first and set apart, so that a tap meant for another icon does not lock (SPEC §6.2).
    val actions = @Composable {
        IconButton(onClick = onLock, modifier = Modifier.padding(end = 10.dp)) { Icon(Icons.Rounded.Lock, contentDescription = "Lock") }
        if (!codesOnly) {
            IconButton(onClick = onGroups) { Icon(Icons.Rounded.FolderOpen, contentDescription = "Groups and tags") }
            // The Codes view, from the app as from the launcher (keething-spec app.codes-view AC3).
            FilledTonalIconButton(onClick = onCodes) { Icon(CodesIcon, contentDescription = "Codes") }
            IconButton(onClick = onSharing) {
                BadgedBox(badge = { if (toAnswer > 0) Badge() }) {
                    Icon(Icons.Rounded.People, contentDescription = if (toAnswer > 0) "Sharing, $toAnswer to answer" else "Sharing")
                }
            }
        }
        MoreMenu(onGenerator, onSettings, onDatabases, openCount)
    }
    val colors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.surface,
        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    )
    if (compact && inTwoPanes()) {
        // A phone in landscape, the list beside the card: the pane leaves room for the title only.
        TopAppBar(
            title = title,
            navigationIcon = navigationIcon,
            actions = { actions() },
            colors = colors,
            scrollBehavior = scroll,
        )
    } else if (compact) {
        TopAppBar(
            title = title,
            subtitle = subtitle,
            navigationIcon = navigationIcon,
            actions = { actions() },
            colors = colors,
            scrollBehavior = scroll,
        )
    } else {
        LargeFlexibleTopAppBar(
            title = title,
            subtitle = subtitle,
            navigationIcon = navigationIcon,
            actions = { actions() },
            colors = colors,
            scrollBehavior = scroll,
        )
    }
}

/** ⋮ in the header: the password generator (the editor's wand), Databases (how many are open) and Settings. */
@Composable
private fun MoreMenu(onGenerator: () -> Unit, onSettings: () -> Unit, onDatabases: () -> Unit, openCount: Int) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Generate a password") },
                leadingIcon = { Icon(Icons.Rounded.AutoFixHigh, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                onClick = {
                    open = false
                    onGenerator()
                },
            )
            DropdownMenuItem(
                text = { Text("Databases") },
                leadingIcon = { Icon(Icons.Rounded.Storage, contentDescription = null) },
                trailingIcon = { Text("$openCount open", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                onClick = {
                    open = false
                    onDatabases()
                },
            )
            DropdownMenuItem(
                text = { Text("Settings") },
                leadingIcon = { Icon(Icons.Rounded.Tune, contentDescription = null) },
                onClick = {
                    open = false
                    onSettings()
                },
            )
        }
    }
}

@Composable
internal fun Banner(title: String, text: String, onDismiss: (() -> Unit)? = null) {
    val colors = MaterialTheme.extraColors
    Row(
        Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(20.dp)).background(colors.warnContainer).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = colors.onWarnContainer)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = colors.onWarnContainer)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.onWarnContainer)
            // Said once read: OK, and it is not said again (keething-spec databases.saf AC5).
            onDismiss?.let { TextButton(onClick = it, modifier = Modifier.align(Alignment.End)) { Text("OK", color = colors.onWarnContainer) } }
        }
    }
}

@Composable
internal fun SectionTitle(title: String) {
    Text(
        title,
        Modifier.padding(start = 8.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * A row of the list; also autofill's entry picker, which neither offers quick actions nor shows the live code
 * ([onLongPress], [onCopyCode] null). [codeUnder]: the code under the title, where the row is too narrow for it beside.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EntryRowView(
    row: EntryRow,
    shape: RoundedCornerShape,
    sharedKey: String,
    icons: CustomIconImages?,
    now: State<Instant>,
    onOpen: () -> Unit,
    onLongPress: (() -> Unit)?,
    onCopyCode: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onOpenLabel: String? = null,
    codeUnder: Boolean = false,
    selected: Boolean = false,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.extraColors.row)
            .semantics { this.selected = selected }
            .combinedClickable(
                onClick = onOpen,
                onClickLabel = onOpenLabel,
                onLongClick = onLongPress,
                onLongClickLabel = onLongPress?.let { "More actions" },
            )
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // The icon at the top, in the height of a title and a line (keething-spec plans/done/android-notes.md AN5): a row
        // grown by a group, a date or a large font keeps it beside the title; a shorter column is centred beside it.
        Box(Modifier.height(ROW_FIRST_LINES), contentAlignment = Alignment.Center) {
            EntryMark(row.avatar, icons?.get(row.customIconId), row.face, 44.dp, Modifier.sharedIcon(sharedKey))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f).heightIn(min = ROW_FIRST_LINES), verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    row.title,
                    Modifier.weight(1f, fill = false).sharedTitle(sharedKey),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (row.hasAttachments) {
                    Icon(
                        Icons.Rounded.AttachFile,
                        contentDescription = "Has attachments",
                        Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            RowSubtitle(row)
            GroupLine(row.database, row.group)
            row.deadline?.let { deadline ->
                val colour = if (deadline.status == ExpiryHint.Status.EXPIRED) MaterialTheme.colorScheme.error else MaterialTheme.extraColors.warn
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Rounded.Schedule, contentDescription = null, Modifier.size(14.dp), tint = colour)
                    Text(deadline.text, style = MaterialTheme.typography.labelLarge, color = colour, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (codeUnder && onCopyCode != null) row.totp?.let { CodeButton(it, now, onCopyCode, Modifier.padding(top = 6.dp), height = 32.dp) }
        }
        if (!codeUnder && onCopyCode != null) row.totp?.let {
            Box(Modifier.height(ROW_FIRST_LINES), contentAlignment = Alignment.Center) { CodeButton(it, now, onCopyCode, Modifier.padding(start = 8.dp)) }
        }
    }
}

/** An entry row's height without its padding: a title and a line, where its icon and code button stay. */
private val ROW_FIRST_LINES = 48.dp

/** A row's line under its title, if any; a protected note's with a lock before it (SPEC §6.4). */
@Composable
internal fun RowSubtitle(row: EntryRow) {
    if (row.subtitle.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        if ((row.face as? EntryFace.Note)?.protected == true) {
            Icon(Icons.Rounded.Lock, contentDescription = null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            row.subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Where an entry is, under its row or its code's tile: while two or more databases are open its database and group
 * ([DatabaseLine]), else its group's path behind a folder, nothing for the root group.
 */
@Composable
internal fun GroupLine(mark: DatabaseMark?, group: List<String>) {
    if (mark != null) {
        DatabaseLine(mark, group)
    } else if (group.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(Icons.Rounded.Folder, contentDescription = "Group", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.outline)
            PathText(group, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.outline)
        }
    }
}

/**
 * A row's database and group while two or more databases are open (keething-spec databases.name-colour AC6; the
 * canvas's "Databases (AD3b)"): its dot, its name in its colour, what tells it apart from one named alike, then the
 * group — "● Work › Engineering", the root group "● Work"; no folder icon.
 */
@Composable
private fun DatabaseLine(mark: DatabaseMark, group: List<String>) {
    val colors = databaseColors(mark.colorIndex)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.padding(end = 6.dp).size(8.dp).clip(CircleShape).background(colors.dot))
        val label = buildAnnotatedString {
            withStyle(SpanStyle(color = colors.text)) { append(mark.label.name) }
            mark.label.addition?.let { withStyle(SpanStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)) { append(" · $it") } }
        }
        if (group.isEmpty()) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
            // The group's path cut to fit after the label, the label whole.
            PathText(group, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.outline, lead = label + AnnotatedString(" › "))
        }
    }
}

/** The live code of a row, with the ring that runs down its period; a tap copies it. */
@Composable
private fun CodeButton(config: TotpConfig, now: State<Instant>, onCopy: () -> Unit, modifier: Modifier, height: Dp = 36.dp) {
    val display = TotpDisplay.of(config, now.value)
    Surface(
        onClick = onCopy,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.semantics { contentDescription = "Copy one-time code" },
    ) {
        Row(
            Modifier.height(height).padding(start = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(display.formatted, style = codeTextStyle())
            CodeRing(display.period, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.outlineVariant, size = 16.dp, stroke = 2.5.dp)
        }
    }
}

/** Next to the search field in the Codes view: Add a code (keething-spec app.codes-view AC7). */
@Composable
private fun AddCodeButton(onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(56.dp),
        shape = RoundedCornerShape(18.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) { Icon(Icons.Rounded.Add, contentDescription = "Add a code") }
}

/**
 * Next to the search field, in thumb reach (the mock-up's list): New entry, New note, New note from clipboard (the agreed
 * menu of three, SPEC §6.4).
 */
@Composable
private fun NewButton(onChoose: (NewChoice) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilledIconButton(
            onClick = { open = true },
            modifier = Modifier.size(56.dp),
            shape = RoundedCornerShape(18.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (open) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
                contentColor = if (open) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        ) { Icon(Icons.Rounded.Add, contentDescription = "New", Modifier.rotate(if (open) 45f else 0f)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = RoundedCornerShape(22.dp)) {
            for (choice in NewChoice.entries) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(choice.title, style = MaterialTheme.typography.bodyLarge)
                            choice.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    },
                    leadingIcon = { NewChoiceTile(choice) },
                    onClick = {
                        open = false
                        onChoose(choice)
                    },
                    modifier = Modifier.heightIn(min = 56.dp),
                )
            }
        }
    }
}

@Composable
private fun NewChoiceTile(choice: NewChoice) {
    val tile = 36.dp
    when (choice) {
        NewChoice.ENTRY -> Box(
            Modifier.size(tile).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Key, contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
        NewChoice.NOTE -> NoteTile(tile)
        NewChoice.NOTE_FROM_CLIPBOARD -> Box(
            Modifier.size(tile).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.extraColors.noteTile),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.ContentPaste, contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.extraColors.onNoteTile) }
    }
}

/**
 * The chips over the keyboard (SPEC §6.2), "Narrow to" before them when they narrow the search; a tap puts its query in
 * the field, the cursor at its end.
 */
@Composable
private fun SearchChipRow(line: ChipLine, onPick: (String) -> Unit) {
    if (line.chips.isEmpty()) return
    LazyRow(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (line.narrowing) {
            item(key = "narrow to") {
                Text(
                    "Narrow to",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(line.chips, key = { it.label + "/" + it.query }) { chip ->
            FilterChip(
                selected = chip.selected,
                onClick = { onPick(chip.query) },
                label = { SearchChipLabel(chip) },
                leadingIcon = chip.database?.let { mark -> { DatabaseDot(mark, 9.dp) } },
                colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.extraColors.row),
            )
        }
    }
}

/**
 * A chip's sign, its name (a database's in its colour) and its count, or its count in each database by its dot when its
 * entries are in two or more (keething-spec search.suggestions AC8); said as "Finanzen: Personal 1, Work 1".
 */
@Composable
private fun SearchChipLabel(chip: SearchChip) {
    val said = when {
        chip.shares.isNotEmpty() -> chip.label + ": " + chip.shares.joinToString { "${it.database.label.full} ${it.count}" }
        chip.database != null -> "Database ${chip.label}" + (chip.count?.let { ", $it" } ?: "")
        else -> null
    }
    Row(
        if (said != null) Modifier.clearAndSetSemantics { contentDescription = said } else Modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            buildAnnotatedString {
                chip.sigil?.let { sigil ->
                    withStyle(SpanStyle(fontFamily = MonospaceFamily, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)) {
                        append(if (sigil == '-') "−" else sigil.toString())
                    }
                    append(" ")
                }
                val name = chip.database?.let { SpanStyle(color = databaseColors(it.colorIndex).text) }
                if (name != null) withStyle(name) { append(chip.label) } else append(chip.label)
                if (chip.shares.isEmpty()) {
                    chip.count?.let { count ->
                        append("  ")
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)) { append(count.toString()) }
                    }
                }
            },
            maxLines = 1,
        )
        for (share in chip.shares) {
            Spacer(Modifier.width(8.dp))
            DatabaseDot(share.database, 7.dp)
            Spacer(Modifier.width(3.dp))
            Text(share.count.toString(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun DatabaseDot(mark: DatabaseMark, size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(databaseColors(mark.colorIndex).dot))
}

/**
 * The search field at the bottom, above the keyboard. [above]: what goes over it (the list's chips);
 * [onFocusChange] hears it take and lose the focus, which it gives up when the keyboard is closed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchField(
    query: TextFieldState,
    placeholder: String = SEARCH_HINT,
    /** What a screen reader calls the field. */
    label: String = "Search entries",
    onFocusChange: (Boolean) -> Unit = {},
    above: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    // Closing the keyboard (Back, its own key) ends the search's focus too, and with it the chips.
    val imeVisible = WindowInsets.isImeVisible
    var imeWasVisible by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (imeWasVisible && !imeVisible) focus.clearFocus()
        imeWasVisible = imeVisible
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
    ) {
        above?.invoke()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .height(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(start = 20.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Rounded.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                // What is searched for names the accounts: the keyboard does not learn it.
                NoLearningKeyboard {
                    BasicTextField(
                        state = query,
                        modifier = Modifier.keepOutOfAutofill()
                            .weight(1f)
                            .onFocusChanged { onFocusChange(it.isFocused) }
                            .semantics { contentDescription = label },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        lineLimits = TextFieldLineLimits.SingleLine,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Search,
                        ),
                        onKeyboardAction = { keyboard?.hide() },
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorator = { field ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (query.text.isEmpty()) {
                                    Text(
                                        placeholder,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                                field()
                            }
                        },
                    )
                }
                if (query.text.isNotEmpty()) {
                    IconButton(onClick = { query.clearText() }) { Icon(Icons.Rounded.Close, contentDescription = "Clear the search") }
                }
            }
            trailing?.invoke()
        }
    }
}
