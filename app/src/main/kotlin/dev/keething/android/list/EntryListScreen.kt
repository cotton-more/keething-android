package dev.keething.android.list

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import dev.keething.android.session.DatabaseSession
import dev.keething.android.theme.MonospaceFamily
import dev.keething.android.theme.extraColors
import dev.keething.android.ui.CodeRing
import dev.keething.android.ui.CustomIconImages
import dev.keething.android.ui.EntryMark
import dev.keething.android.ui.FitText
import dev.keething.android.ui.NoLearningKeyboard
import dev.keething.android.ui.Notice
import dev.keething.android.ui.NoticeEffect
import dev.keething.android.ui.NoticeHost
import dev.keething.android.ui.SegmentGap
import dev.keething.android.ui.rememberSecondsNow
import dev.keething.android.ui.segmentShape
import dev.keething.card.ExpiryHint
import dev.keething.card.TotpDisplay
import dev.keething.domain.totp.TotpConfig
import dev.keething.search.EntrySearch
import java.text.NumberFormat
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * The list (SPEC §6.2): nothing typed shows the recently used entries, then all by title; the search field
 * is at the bottom, in thumb reach above the keyboard, with the filters as chips over it while it has the focus.
 * A row opens its card; swiped right it copies the password, left the username; a long press offers the quick
 * actions; a row with a one-time code shows it live, as a button that copies it. [codesOnly]: the "Codes"
 * shortcut's list, only the entries with a one-time code; Back or the arrow leaves it for the whole list.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EntryListScreen(
    container: AppContainer,
    database: DatabaseSession,
    query: TextFieldState,
    codesOnly: Boolean,
    selectedId: String?,
    onOpen: (id: String, origin: String) -> Unit,
    onEdit: (id: String) -> Unit,
    onSettings: () -> Unit,
    onGroups: () -> Unit,
    onLeaveCodes: () -> Unit,
    onNewEntry: () -> Unit,
    onSharing: () -> Unit,
) {
    val codes by rememberUpdatedState(codesOnly)
    // Beside the card (two panes), the open entry's rows are marked.
    val selected = selectedId?.takeIf { inTwoPanes() }
    val holdRecent by rememberUpdatedState(selected != null)
    val requests by container.shareRequests.book.collectAsStateWithLifecycle()
    val model = viewModel { EntryListViewModel(database, container.recents, container.recycleBin, container.clipboard, query, codesOnly = { codes }, besideCard = { holdRecent }) }
    val state by model.state.collectAsStateWithLifecycle()
    val chips by model.chips.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    // The screen's own problems (no app opens a link): shown as the model's are.
    val problems = remember { Channel<Notice>(Channel.BUFFERED) }
    NoticeEffect(model.notices, snackbar)
    NoticeEffect(container.recycleBin.notices, snackbar)
    NoticeEffect(remember { problems.receiveAsFlow() }, snackbar)
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

    // The quick actions of a long-pressed row, with the section it was in (Remove from Recent is only for a recent row).
    var quick by remember { mutableStateOf<Pair<QuickActions, ListSection.Kind>?>(null) }
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

    // Landscape on a phone: one row fits, so the header is compact from the start.
    val compactHeader = LocalConfiguration.current.screenHeightDp < 480
    // The large header collapses into a compact bar while the list scrolls, and is opaque once anything is under it.
    val scroll = if (compactHeader) TopAppBarDefaults.pinnedScrollBehavior() else TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var searchFocused by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // The keyboard takes half the screen: the large header makes room as the search field takes the focus, and comes
    // back when it lets the focus go with the list at its top.
    LaunchedEffect(searchFocused) {
        if (compactHeader) return@LaunchedEffect
        val atTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        if (!searchFocused && !atTop) return@LaunchedEffect
        val bar = scroll.state
        animate(bar.heightOffset, if (searchFocused) bar.heightOffsetLimit else 0f) { value, _ -> bar.heightOffset = value }
    }
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Header(
                state, scroll, codesOnly, compactHeader, onLock = container.sessions::lock, onGenerator = { generating = true }, onGroups = onGroups,
                onSettings = onSettings, onLeaveCodes = onLeaveCodes, onSharing = onSharing, toAnswer = requests?.theirs?.size ?: 0,
            )
        },
        bottomBar = {
            SearchField(
                query,
                placeholder = if (codesOnly) "Search entries with a code" else SEARCH_HINT,
                onFocusChange = { searchFocused = it },
                above = { if (searchFocused) SearchChipRow(chips) { query.setTextAndPlaceCursorAtEnd(it) } },
            ) {
                if (!codesOnly) NewEntryButton(onNewEntry)
            }
        },
        snackbarHost = { NoticeHost(snackbar) },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val codeUnder = codeGoesUnder(maxWidth)
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 16.dp),
            ) {
                if (state.conflictCopies.isNotEmpty()) {
                    item(key = "conflict") {
                        val newest = state.conflictCopies.first()
                        Banner(
                            title = "Syncthing found two versions",
                            text = "Open: ${state.databaseName}. The other one is from ${newest.createdAt.format(CONFLICT_TIME)} " +
                                "(device ${newest.deviceId}) — merge it in KeePassXC (Database › Merge), then delete it.",
                        )
                    }
                }
                state.reloadFailure?.let { failure ->
                    item(key = "reload") {
                        Banner(title = "Showing the version read before", text = model.describe(failure))
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
                                onCopyCode = { model.copyCode(row.id) },
                                codeUnder = codeUnder,
                            )
                        }
                    }
                    item(key = "gap/${section.kind}", contentType = "gap") { Spacer(Modifier.height(18.dp)) }
                }
                if (!state.preparing && state.sections.isEmpty() && codesOnly && query.text.isBlank()) {
                    item(key = "no codes") {
                        Text(
                            "No entry has a one-time code.",
                            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (!state.preparing && state.sections.isEmpty() && query.text.isNotBlank()) {
                    item(key = "nothing") {
                        val emptyBin = EntrySearch.recycleBinQuery(query.text.toString())?.isBlank() == true
                        Text(
                            when {
                                emptyBin -> "The recycle bin is empty."
                                codesOnly -> "No entry with a one-time code matches “${query.text}”."
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

private val CONFLICT_TIME: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

internal fun ListSection.title(): String = when (kind) {
    ListSection.Kind.SUGGESTED -> "Suggested"
    ListSection.Kind.RECENT -> "Recent"
    ListSection.Kind.ALL -> "All entries"
    ListSection.Kind.RESULTS -> if (rows.size == 1) "1 entry" else "${NumberFormat.getIntegerInstance().format(rows.size)} entries"
    ListSection.Kind.RECYCLE_BIN -> "Recycle bin"
    ListSection.Kind.CODES -> "Entries with a one-time code"
}

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
    onLeaveCodes: () -> Unit,
    onSharing: () -> Unit,
    /** Requests waiting for an answer (SPEC §10): a dot on Sharing. */
    toAnswer: Int,
) {
    val count = state.entryCount?.let { " · ${NumberFormat.getIntegerInstance().format(it)} entries" }.orEmpty()
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
            IconButton(onClick = onSharing) {
                BadgedBox(badge = { if (toAnswer > 0) Badge() }) {
                    Icon(Icons.Rounded.People, contentDescription = if (toAnswer > 0) "Sharing, $toAnswer to answer" else "Sharing")
                }
            }
        }
        MoreMenu(onGenerator, onSettings)
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

/** ⋮ in the header: the password generator (the editor's wand) and Settings. */
@Composable
private fun MoreMenu(onGenerator: () -> Unit, onSettings: () -> Unit) {
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
private fun Banner(title: String, text: String) {
    val colors = MaterialTheme.extraColors
    Row(
        Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(20.dp)).background(colors.warnContainer).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = colors.onWarnContainer)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = colors.onWarnContainer)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.onWarnContainer)
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
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EntryMark(row.avatar, icons?.get(row.customIconId), row.face, 44.dp, Modifier.sharedIcon(sharedKey))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
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
            if (row.subtitle.isNotEmpty()) {
                Text(
                    row.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (row.group.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Rounded.Folder, contentDescription = "Group", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.outline)
                    Text(
                        row.group,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            row.deadline?.let { deadline ->
                val colour = if (deadline.status == ExpiryHint.Status.EXPIRED) MaterialTheme.colorScheme.error else MaterialTheme.extraColors.warn
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Rounded.Schedule, contentDescription = null, Modifier.size(14.dp), tint = colour)
                    Text(deadline.text, style = MaterialTheme.typography.labelLarge, color = colour, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (codeUnder && onCopyCode != null) row.totp?.let { CodeButton(it, now, onCopyCode, Modifier.padding(top = 6.dp), height = 32.dp) }
        }
        if (!codeUnder && onCopyCode != null) row.totp?.let { CodeButton(it, now, onCopyCode, Modifier.padding(start = 8.dp)) }
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

/** Next to the search field, in thumb reach (the mock-up's list). */
@Composable
private fun NewEntryButton(onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(56.dp),
        shape = RoundedCornerShape(18.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) { Icon(Icons.Rounded.Add, contentDescription = "New entry") }
}

/** The chips over the keyboard (SPEC §6.2); a tap puts its query in the field, the cursor at its end. */
@Composable
private fun SearchChipRow(chips: List<SearchChip>, onPick: (String) -> Unit) {
    if (chips.isEmpty()) return
    LazyRow(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(chips, key = { it.label + "/" + it.query }) { chip ->
            FilterChip(
                selected = chip.selected,
                onClick = { onPick(chip.query) },
                label = {
                    Text(
                        buildAnnotatedString {
                            chip.sigil?.let { sigil ->
                                withStyle(SpanStyle(fontFamily = MonospaceFamily, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)) {
                                    append(if (sigil == '-') "−" else sigil.toString())
                                }
                                append(" ")
                            }
                            append(chip.label)
                            chip.count?.let { count ->
                                append("  ")
                                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)) { append(count.toString()) }
                            }
                        },
                        maxLines = 1,
                    )
                },
                colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.extraColors.row),
            )
        }
    }
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
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { onFocusChange(it.isFocused) }
                            .semantics { contentDescription = "Search entries" },
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
