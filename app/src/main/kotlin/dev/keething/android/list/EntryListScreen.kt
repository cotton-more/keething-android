package dev.keething.android.list

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.keething.android.session.isReadOnly
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import dev.keething.android.ui.HoldPortrait
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
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
import dev.keething.android.R
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
import dev.keething.android.ui.DIMMED
import dev.keething.android.ui.codeFill
import androidx.compose.ui.draw.alpha
import dev.keething.android.ui.CodesIcon
import dev.keething.android.ui.CustomIconImages
import dev.keething.android.ui.EntryMark
import dev.keething.android.ui.FitText
import dev.keething.android.ui.NoLearningKeyboard
import dev.keething.android.ui.PathText
import androidx.compose.ui.text.AnnotatedString
import dev.keething.android.ui.Notice
import dev.keething.android.ui.resolve
import dev.keething.android.ui.uiPlain
import dev.keething.android.ui.uiText
import dev.keething.android.databases.NotOpenDatabase
import dev.keething.android.databases.NotOpenLine
import dev.keething.android.databases.NotOpenSheet
import dev.keething.android.databases.ReadOnlyDatabase
import dev.keething.android.databases.ReadOnlySheet
import dev.keething.android.databases.rememberFileActions
import dev.keething.android.databases.Reach
import dev.keething.android.ui.keepOutOfAutofill
import dev.keething.android.unlock.FingerprintQuestion
import dev.keething.android.unlock.ReminderKind
import dev.keething.android.unlock.ReminderNotMatched
import dev.keething.android.unlock.ReminderQuestion
import dev.keething.android.unlock.ReminderQuestionModel
import dev.keething.vault.biometric.FingerprintAnswer
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.DatabaseSession
import dev.keething.vault.session.SessionState
import dev.keething.vault.databases.EntryRef
import androidx.compose.material.icons.rounded.Storage
import dev.keething.android.ui.NoticeEffect
import dev.keething.android.ui.NoticeHost
import dev.keething.android.ui.SegmentGap
import dev.keething.android.ui.rememberSecondsNow
import dev.keething.android.ui.segmentShape
import dev.keething.card.EntryFace
import dev.keething.card.ExpiryHint
import dev.keething.card.TotpDisplay
import dev.keething.search.EntrySearch
import dev.keething.search.FilterCompletion
import dev.keething.search.FilterKind
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
    /** The open ones that are read-only, on the same line after them (keething-spec databases.read-only-mark AC4). */
    readOnly: List<ReadOnlyDatabase> = emptyList(),
    openCount: Int = 1,
    canClose: Boolean = false,
    /** A database's lock screen, with Back (a chip's tap, Unlock… in its sheet). */
    onLockScreen: (DatabaseId) -> Unit = {},
    onCloseDatabase: (DatabaseId) -> Unit = {},
    onDatabases: () -> Unit = {},
) {
    // Beside the card (two panes), the open entry's rows are marked.
    val selected = selectedId?.takeIf { inTwoPanes() }
    val requests by container.shareRequests.book.collectAsStateWithLifecycle()
    val unnamedCode = stringResource(R.string.list_one_time_code)
    val model = viewModel {
        val sessions = container.sessions
        EntryListViewModel(
            sessions.open, sessions.state, sessions::entry, container.recents, container.toldUnlocks, container.recycleBin,
            container.clipboard, query,
            codesOnlyAtStart = codesOnly, besideCardAtStart = selected != null, databases = sessions.list.state,
            newEntryMade = { container.settings.lastNewEntryDatabase = it }, unnamedCode = unnamedCode,
        )
    }
    SideEffect {
        model.codesOnly = codesOnly
        model.besideCard = selected != null
    }
    // A phone holds the Codes view upright: sideways it only spreads the tiles (Android 16 ignores it on a large screen).
    if (codesOnly) HoldPortrait()
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
    val files = rememberFileActions(container, onProblem = { problem -> problem?.let { container.windowNotices.trySend(Notice(uiPlain(it), isProblem = true)) } })
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
    // A read-only database's chip: its sheet (keething-spec databases.read-only-mark AC4).
    var readOnlySheetOf by rememberSaveable { mutableStateOf<String?>(null) }
    readOnly.firstOrNull { it.id.value == readOnlySheetOf }?.let { database ->
        val showOnly = FilterCompletion.token(FilterKind.DATABASE, database.line.label.full)
        val sessions = container.sessions
        ReadOnlySheet(
            database,
            showOnly = showOnly,
            onLockOthers = {
                readOnlySheetOf = null
                sessions.lockOthers(database.id)
            },
            onUpdate = {
                readOnlySheetOf = null
                sessions.session(database.id)?.let(sessions.kdbx3::ask)
            },
            onShowOnly = {
                readOnlySheetOf = null
                query.setTextAndPlaceCursorAtEnd("$showOnly ") // not user text
            },
            onLock = {
                readOnlySheetOf = null
                sessions.databases.lock(database.id)
            },
            onDismiss = { readOnlySheetOf = null },
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
                    is FingerprintAnswer.Failed -> Notice(uiText(R.string.list_fingerprint_failed, answer.reason), isProblem = true)
                    FingerprintAnswer.TurnedOn -> Notice(uiText(R.string.list_fingerprint_on, name))
                    FingerprintAnswer.Declined, FingerprintAnswer.Nothing -> null
                }
                notice?.let(container.windowNotices::trySend)
            }
        }
    }

    // The reminder's question, once a database whose days are up is open (keething-spec unlock.reminder-not-a-lock):
    // one at a time, after the fingerprint's.
    val reminderAsked by container.sessions.reminderQuestion.collectAsStateWithLifecycle()
    val reminderScope = rememberCoroutineScope()
    // Three tries that did not match: said until closed, though the question is put off by then.
    var notMatched by remember { mutableStateOf<DatabaseSession?>(null) }
    val reminder = remember(reminderAsked) {
        reminderAsked?.let { database ->
            val record = container.sessions.list.record(database.id)
            val name = record?.displayName ?: database.location.fileName
            val biometric = database.biometric.current()
            ReminderQuestionModel(
                database = database,
                name = name,
                kind = ReminderKind.of(biometric.passwordless, record?.keyFile?.fileName),
                days = container.sessions.biometric.daysSince(database.id),
                interval = biometric.reminderDays,
                file = database.location.fileName,
                scope = reminderScope,
                said = { container.windowNotices.trySend(Notice(it)) },
                failed = { notMatched = database },
            )
        }
    }
    DisposableEffect(reminder) { onDispose { reminder?.clear() } }
    notMatched?.let { database ->
        val state by database.session.state.collectAsStateWithLifecycle()
        if (state !is SessionState.Unlocked) {
            notMatched = null
            return@let
        }
        ReminderNotMatched(
            name = container.sessions.list.record(database.id)?.displayName ?: database.location.fileName,
            onChangeMasterKey = {
                notMatched = null
                container.masterKeys.open(database.id)
            },
            onClose = { notMatched = null },
        )
    } ?: reminder?.takeIf { question == null }?.let { ReminderQuestion(it) }

    // The codes of a row whose entry has two or more, opened by its code button or its quick actions (keething-spec
    // search.row-codes AC4).
    var codesOf by remember { mutableStateOf<EntryRow?>(null) }
    codesOf?.let { row ->
        RowCodesSheet(row, state.icons, now, onCopy = model::copyCode, onCopyNext = model::copyNextCode, onDismiss = { codesOf = null })
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
                override fun copyCode(tile: CodeTile) = model.copyCode(tile)
                override fun showCodes(row: EntryRow) {
                    codesOf = row
                }
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
                        problems.trySend(Notice(uiText(R.string.list_no_app_for_link), isProblem = true))
                    }
                }
            },
            onDismiss = { quick = null },
        )
    }

    // Resolve… on a conflict copy's banner (keething-spec kdbx.conflict-copies AC6).
    val conflict by model.conflict.collectAsStateWithLifecycle()
    conflict?.let { ConflictCopySheet(it, onDismiss = model::closeConflict) }

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
            // A database read-only refuses it before its sheet (keething-spec app.large-database AC3, kdbx.kdbx3 AC6).
            onEdit = {
                val database = container.sessions.session(EntryRef.of(tile.entryId).database)
                if (database?.isReadOnly == true) container.sessions.readOnlyRefusal(database) else editing = tile
            },
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
        // Only databases read-only are open: a code has nowhere to go (as a new entry, KeethingApp's onNew).
        if (places.isNotEmpty()) adding = true else openDatabases.firstOrNull()?.let { container.sessions.readOnlyRefusal(it.database) }
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
                NotOpenLine(
                    notOpen, readOnly, typing = query.text.isNotEmpty(), onTap = onLockScreen, onLongPress = { sheetOf = it.value },
                    onReadOnly = { readOnlySheetOf = it.value },
                )
            }
        },
        bottomBar = {
            SearchField(
                query,
                placeholder = stringResource(if (codesOnly) R.string.list_search_codes else R.string.list_search),
                label = stringResource(if (codesOnly) R.string.list_search_codes else R.string.list_search_entries),
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
                    onCopyNext = model::copyNextCode,
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
                    item(key = banner.key) {
                        Banner(banner.title.resolve(), banner.text.resolve(), banner.onDismiss, banner.action?.let { (label, act) -> label.resolve() to act })
                    }
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
                                onCode = { if (row.codes.size == 1) model.copyCode(row.codes.single()) else codesOf = row },
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
                                emptyBin -> stringResource(R.string.list_bin_empty)
                                else -> stringResource(R.string.list_nothing_matches, query.text)
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

@Composable
internal fun ListSection.title(): String = when (kind) {
    ListSection.Kind.SUGGESTED -> stringResource(R.string.list_section_suggested)
    ListSection.Kind.RECENT -> stringResource(R.string.list_section_recent)
    ListSection.Kind.ALL -> stringResource(R.string.list_section_all)
    ListSection.Kind.RESULTS -> pluralStringResource(R.plurals.list_section_results, rows.size, NumberFormat.getIntegerInstance().format(rows.size))
    ListSection.Kind.RECYCLE_BIN -> stringResource(R.string.list_section_recycle_bin)
}

/** The card's origin for an entry opened from the Codes view: no row of the list to grow from. */
private const val CODES_ORIGIN = "codes" // not user text

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
    val name = state.databaseName.resolve()
    val counted = when {
        codesOnly -> if (state.preparing) {
            name
        } else {
            val codes = state.codes.size
            pluralStringResource(R.plurals.list_header_code_count, codes, name, NumberFormat.getIntegerInstance().format(codes))
        }
        else -> state.entryCount?.let { pluralStringResource(R.plurals.list_header_entry_count, it, name, NumberFormat.getIntegerInstance().format(it)) } ?: name
    }
    val title = @Composable { Text(stringResource(if (codesOnly) R.string.list_header_codes else R.string.app_name), maxLines = 1, overflow = TextOverflow.Ellipsis) }
    val subtitle = @Composable { FitText(counted) }
    val navigationIcon = @Composable {
        if (codesOnly) {
            IconButton(onClick = onLeaveCodes) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.list_section_all)) }
        }
    }
    // Lock first and set apart, so that a tap meant for another icon does not lock (SPEC §6.2).
    val actions = @Composable {
        IconButton(onClick = onLock, modifier = Modifier.padding(end = 10.dp)) { Icon(Icons.Rounded.Lock, contentDescription = stringResource(R.string.list_lock)) }
        if (!codesOnly) {
            IconButton(onClick = onGroups) { Icon(Icons.Rounded.FolderOpen, contentDescription = stringResource(R.string.list_groups_and_tags)) }
            // The Codes view, from the app as from the launcher (keething-spec app.codes-view AC3).
            IconButton(onClick = onCodes) { Icon(CodesIcon, contentDescription = stringResource(R.string.list_header_codes)) }
            IconButton(onClick = onSharing) {
                BadgedBox(badge = { if (toAnswer > 0) Badge() }) {
                    Icon(Icons.Rounded.People, contentDescription = if (toAnswer > 0) pluralStringResource(R.plurals.list_sharing_to_answer, toAnswer, toAnswer) else stringResource(R.string.list_sharing))
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
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.list_more)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.list_generate_password)) },
                leadingIcon = { Icon(Icons.Rounded.AutoFixHigh, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                onClick = {
                    open = false
                    onGenerator()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.list_databases)) },
                leadingIcon = { Icon(Icons.Rounded.Storage, contentDescription = null) },
                trailingIcon = { Text(pluralStringResource(R.plurals.list_databases_open, openCount, openCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                onClick = {
                    open = false
                    onDatabases()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.list_settings)) },
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
internal fun Banner(title: String, text: String, onDismiss: (() -> Unit)? = null, action: Pair<String, () -> Unit>? = null) {
    val colors = MaterialTheme.extraColors
    Row(
        Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(20.dp)).background(colors.warnContainer).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = colors.onWarnContainer)
        // Announced when it appears, its title and text as one (keething-spec app.accessibility AC6).
        Column(
            Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = colors.onWarnContainer)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.onWarnContainer)
            // Said once read: OK, and it is not said again (keething-spec databases.saf AC5).
            onDismiss?.let { TextButton(onClick = it, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.list_ok), color = colors.onWarnContainer) } }
            action?.let { (label, act) -> TextButton(onClick = act, modifier = Modifier.align(Alignment.End)) { Text(label, color = colors.onWarnContainer) } }
        }
    }
}

@Composable
internal fun SectionTitle(title: String) {
    Text(
        title,
        Modifier.padding(start = 8.dp, bottom = 8.dp).semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * A row of the list; also autofill's entry picker, which neither offers quick actions nor shows the live code
 * ([onLongPress], [onCode] null). [onCode]: the code button, which copies the one code or opens two or more
 * (keething-spec search.row-codes). [codeUnder]: the code under the title, where the row is too narrow for it beside.
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
    onCode: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onOpenLabel: String? = null,
    codeUnder: Boolean = false,
    selected: Boolean = false,
) {
    val moreActions = stringResource(R.string.list_more_actions)
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
                onLongClickLabel = onLongPress?.let { moreActions },
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
                        contentDescription = stringResource(R.string.list_has_attachments),
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
                    Text(deadline.text.resolve(), style = MaterialTheme.typography.labelLarge, color = colour, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (codeUnder && onCode != null && row.codes.isNotEmpty()) CodeButton(row.codes, now, onCode, Modifier.padding(top = 6.dp), height = 32.dp)
        }
        if (!codeUnder && onCode != null && row.codes.isNotEmpty()) {
            Box(Modifier.height(ROW_FIRST_LINES), contentAlignment = Alignment.Center) { CodeButton(row.codes, now, onCode, Modifier.padding(start = 8.dp)) }
        }
    }
}

/** An entry row's height without its padding: a title and a line, where its icon and code button stay. */
private val ROW_FIRST_LINES = 48.dp

/** A row's line under its title, if any; a protected note's with a lock before it (SPEC §6.4). */
@Composable
internal fun RowSubtitle(row: EntryRow) {
    val subtitle = row.subtitle.resolve()
    if (subtitle.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        if ((row.face as? EntryFace.Note)?.protected == true) {
            Icon(Icons.Rounded.Lock, contentDescription = null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            subtitle,
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
            Icon(Icons.Rounded.Folder, contentDescription = stringResource(R.string.list_group), Modifier.size(14.dp), tint = MaterialTheme.colorScheme.outline)
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

/**
 * The code button of a row: the first of its entry's [codes] live on a fill that drains over its period (keething-spec
 * card.code-timer AC4). One code: a tap copies it; two or more: their number beside it, and a tap opens them all
 * (search.row-codes AC2, AC3), said as "2 one-time codes" with the action "Show codes" (AC6).
 */
@Composable
private fun CodeButton(codes: List<CodeTile>, now: State<Instant>, onClick: () -> Unit, modifier: Modifier, height: Dp = 36.dp) {
    val display = TotpDisplay.of(codes.first().totp, now.value)
    val several = codes.size > 1
    val colors = MaterialTheme.extraColors
    val said = if (several) codeCount(codes.size) else stringResource(R.string.list_copy_one_time_code)
    val showCodes = stringResource(R.string.list_show_codes)
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .codeFill(display.period, CircleShape)
            .clickable(onClickLabel = if (several) showCodes else null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = said }
            .height(height)
            .padding(start = 12.dp, end = if (several) 6.dp else 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(display.formatted, Modifier.alpha(if (display.secondsRemaining <= 5) DIMMED else 1f), style = codeTextStyle(), color = colors.onCodePill)
            if (several) {
                // The count, in the digits' colour; the description says it, so the reader does not read it again.
                Box(
                    Modifier.clearAndSetSemantics {}.height(height - 12.dp).widthIn(min = height - 12.dp).clip(CircleShape)
                        .background(colors.onCodePill).padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        codes.size.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    )
                }
            }
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
    ) { Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.list_add_a_code)) }
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
        ) { Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.list_new), Modifier.rotate(if (open) 45f else 0f)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = RoundedCornerShape(22.dp)) {
            for (choice in NewChoice.entries) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(choice.title.resolve(), style = MaterialTheme.typography.bodyLarge)
                            choice.subtitle?.let { Text(it.resolve(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
            item(key = "narrow to") { // not user text
                Text(
                    stringResource(R.string.list_narrow_to),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(line.chips, key = { "${it.label}/${it.query}" }) { chip ->
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
    val label = chip.label.resolve()
    val said = when {
        chip.shares.isNotEmpty() -> stringResource(
            R.string.list_chip_shares,
            label,
            chip.shares.map { stringResource(R.string.list_chip_share, it.database.label.full, it.count) }.joinToString(stringResource(R.string.list_separator)),
        )
        chip.database != null -> chip.count?.let { stringResource(R.string.list_chip_database_count_said, label, it) } ?: stringResource(R.string.list_chip_database_said, label)
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
                if (name != null) withStyle(name) { append(label) } else append(label)
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
    placeholder: String = stringResource(R.string.list_search),
    /** What a screen reader calls the field. */
    label: String = stringResource(R.string.list_search_entries),
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
                    IconButton(onClick = { query.clearText() }) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.list_clear_search)) }
                }
            }
            trailing?.invoke()
        }
    }
}
