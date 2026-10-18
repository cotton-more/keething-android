package dev.keepassbar.android.list

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.keepassbar.android.AppContainer
import dev.keepassbar.android.navigation.sharedIcon
import dev.keepassbar.android.navigation.sharedTitle
import dev.keepassbar.android.session.DatabaseSession
import dev.keepassbar.android.theme.MonospaceFamily
import dev.keepassbar.android.theme.extraColors
import dev.keepassbar.android.ui.CodeRing
import dev.keepassbar.android.ui.CustomIconImages
import dev.keepassbar.android.ui.EntryIcon
import dev.keepassbar.android.ui.NoticeEffect
import dev.keepassbar.android.ui.NoticeHost
import dev.keepassbar.android.ui.SegmentGap
import dev.keepassbar.android.ui.rememberSecondsNow
import dev.keepassbar.android.ui.segmentShape
import dev.keepassbar.card.ExpiryHint
import dev.keepassbar.card.TotpDisplay
import dev.keepassbar.domain.totp.TotpConfig
import dev.keepassbar.search.EntrySearch
import java.text.NumberFormat
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The list (SPEC §6.2): nothing typed shows the recently used entries, then all by title; the search field
 * is at the bottom, in thumb reach above the keyboard. A row opens its card; a long press copies the
 * password; a row with a one-time code shows it live, as a button that copies it.
 */
@Composable
internal fun EntryListScreen(
    container: AppContainer,
    database: DatabaseSession,
    query: TextFieldState,
    onOpen: (id: String, origin: String) -> Unit,
    onSettings: () -> Unit,
) {
    val model = viewModel { EntryListViewModel(database, container.recents, container.clipboard, query) }
    val state by model.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    NoticeEffect(model.notices, snackbar)
    BackHandler(enabled = query.text.isNotEmpty()) { query.clearText() }
    val now = rememberSecondsNow()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { Header(state, onLock = container.sessions::lock, onSettings = onSettings) },
        bottomBar = { SearchField(query) },
        snackbarHost = { NoticeHost(snackbar) },
    ) { padding ->
        LazyColumn(
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
                    EntryRowView(
                        row = row,
                        shape = segmentShape(index, section.rows.size),
                        sharedKey = "${section.kind}/${row.id}",
                        icons = state.icons,
                        now = now,
                        onOpen = { onOpen(row.id, section.kind.name) },
                        onCopyPassword = { model.copyPassword(row.id) },
                        onCopyCode = { model.copyCode(row.id) },
                        modifier = Modifier.padding(bottom = SegmentGap),
                    )
                }
                item(key = "gap/${section.kind}", contentType = "gap") { Spacer(Modifier.height(18.dp)) }
            }
            if (!state.preparing && state.sections.isEmpty() && query.text.isNotBlank()) {
                item(key = "nothing") {
                    val emptyBin = EntrySearch.recycleBinQuery(query.text.toString())?.isBlank() == true
                    Text(
                        if (emptyBin) "The recycle bin is empty." else "Nothing matches “${query.text}”.",
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private val CONFLICT_TIME: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

private fun ListSection.title(): String = when (kind) {
    ListSection.Kind.RECENT -> "Recent"
    ListSection.Kind.ALL -> "All entries"
    ListSection.Kind.RESULTS -> if (rows.size == 1) "1 entry" else "${NumberFormat.getIntegerInstance().format(rows.size)} entries"
    ListSection.Kind.RECYCLE_BIN -> "Recycle bin"
}

@Composable
private fun Header(state: ListUiState, onLock: () -> Unit, onSettings: () -> Unit) {
    Row(
        Modifier.statusBarsPadding().padding(start = 24.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("KeePassBar", style = MaterialTheme.typography.headlineMedium)
            val count = state.entryCount?.let { " · ${NumberFormat.getIntegerInstance().format(it)} entries" }.orEmpty()
            Text(
                state.databaseName + count,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onLock) { Icon(Icons.Rounded.Lock, contentDescription = "Lock") }
        IconButton(onClick = onSettings) { Icon(Icons.Rounded.Tune, contentDescription = "Settings") }
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
private fun SectionTitle(title: String) {
    Text(
        title,
        Modifier.padding(start = 8.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRowView(
    row: EntryRow,
    shape: RoundedCornerShape,
    sharedKey: String,
    icons: CustomIconImages?,
    now: State<Instant>,
    onOpen: () -> Unit,
    onCopyPassword: () -> Unit,
    onCopyCode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(shape)
            .background(MaterialTheme.extraColors.row)
            .combinedClickable(onClick = onOpen, onLongClick = onCopyPassword, onLongClickLabel = "Copy password")
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EntryIcon(row.avatar, icons?.get(row.customIconId), 44.dp, Modifier.sharedIcon(sharedKey))
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
            row.deadline?.let { deadline ->
                val colour = if (deadline.status == ExpiryHint.Status.EXPIRED) MaterialTheme.colorScheme.error else MaterialTheme.extraColors.warn
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Rounded.Schedule, contentDescription = null, Modifier.size(14.dp), tint = colour)
                    Text(deadline.text, style = MaterialTheme.typography.labelLarge, color = colour, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        row.totp?.let { CodeButton(it, now, onCopyCode) }
    }
}

/** The live code of a row, with the ring that runs down its period; a tap copies it. */
@Composable
private fun CodeButton(config: TotpConfig, now: State<Instant>, onCopy: () -> Unit) {
    val display = TotpDisplay.of(config, now.value)
    Surface(
        onClick = onCopy,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.padding(start = 8.dp).semantics { contentDescription = "Copy one-time code" },
    ) {
        Row(
            Modifier.height(48.dp).padding(start = 12.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(display.formatted, fontFamily = MonospaceFamily, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
            CodeRing(display.period, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun SearchField(query: TextFieldState) {
    val keyboard = LocalSoftwareKeyboardController.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            Modifier
                .weight(1f)
                .height(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(start = 20.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            BasicTextField(
                state = query,
                modifier = Modifier.weight(1f).semantics { contentDescription = "Search entries" },
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
                                "@group · #tag · +file · -deleted",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        field()
                    }
                },
            )
            if (query.text.isNotEmpty()) {
                IconButton(onClick = { query.clearText() }) { Icon(Icons.Rounded.Close, contentDescription = "Clear the search") }
            }
        }
    }
}
