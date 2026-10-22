package dev.keepassbar.android.entry

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.keepassbar.android.AppContainer
import dev.keepassbar.android.attachment.SaveAttachment
import dev.keepassbar.android.navigation.sharedIcon
import dev.keepassbar.android.navigation.sharedTitle
import dev.keepassbar.android.session.DatabaseSession
import dev.keepassbar.android.theme.extraColors
import dev.keepassbar.android.ui.EntryIcon
import dev.keepassbar.android.ui.NoticeEffect
import dev.keepassbar.android.ui.NoticeHost
import dev.keepassbar.android.ui.rememberSecondsNow
import dev.keepassbar.card.CardSection
import dev.keepassbar.card.EntryCard
import dev.keepassbar.card.EntryExpiry
import dev.keepassbar.card.ExpiryHint
import dev.keepassbar.card.FieldRow
import dev.keepassbar.search.FilterCompletion
import dev.keepassbar.search.FilterKind
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * An entry's card (SPEC §6.2), laid out by the Mac app's rules (`core/card`): sections, masked values with
 * an eye, live codes, tap to copy, links that open the browser, attachments to open in another app or save.
 * The password's menu shows it as a QR code or in large type ([onShowPassword]), or shares it after a
 * warning. The bar shows the entry's earlier versions ([onHistory]), edits it ([onEdit]) or moves it into the
 * recycle bin at once, the list then offering Undo; an entry in the bin has Restore instead.
 */
@Composable
internal fun EntryCardScreen(
    container: AppContainer,
    database: DatabaseSession,
    entryId: String,
    origin: String,
    onBack: () -> Unit,
    onSearch: (String) -> Unit,
    onShowPassword: (PasswordView) -> Unit,
    onEdit: () -> Unit,
    onHistory: () -> Unit,
) {
    val model = viewModel {
        EntryCardViewModel(
            entryId, database, container.recents, container.clipboard, container.attachmentFiles, container.androidAttachments,
            container.recycleBin,
        )
    }
    val state by model.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    NoticeEffect(model.notices, snackbar)
    val context = LocalContext.current
    val saveDialog = rememberLauncherForActivityResult(SaveAttachment(), model::saved)
    LaunchedEffect(model) {
        model.openings.collect { intent ->
            try {
                context.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                model.problem("No app opens this file; save it instead")
            }
        }
    }
    val content = state.content
    // Deleted elsewhere and read again, or locked: there is nothing to show.
    LaunchedEffect(content == null) { if (content == null) onBack() }
    if (content == null) return

    val openLink: (String) -> Unit = { url ->
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            model.problem("No app opens this link")
        }
    }
    val now = rememberSecondsNow()
    var sharing by rememberSaveable { mutableStateOf(false) }
    val actions = CardActions(
        onCopy = model::copy,
        onToggle = model::toggleReveal,
        onToggleSection = model::toggleSection,
        onOpenLink = openLink,
        onOpenAttachment = model::openAttachment,
        onSaveAttachment = { row -> saveDialog.launch(model.saveRequest(row)) },
        onPasswordMenu = { action ->
            when (action) {
                PasswordAction.QR_CODE -> onShowPassword(PasswordView.QR_CODE)
                PasswordAction.LARGE_TYPE -> onShowPassword(PasswordView.LARGE_TYPE)
                PasswordAction.SHARE -> sharing = true
            }
        },
        onSetUsed = if (state.content?.inRecycleBin == false) model::setUsed else null,
        marking = model.marking,
    )
    if (sharing) {
        ShareWarning(
            onShare = {
                sharing = false
                model.passwordToShare()?.let { password ->
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, password)
                    context.startActivity(Intent.createChooser(send, null))
                }
            },
            onShowQrCode = {
                sharing = false
                onShowPassword(PasswordView.QR_CODE)
            },
            onDismiss = { sharing = false },
        )
    }

    // The bar is clear at the top, opaque once the card scrolls under it; the entry's title moves into it
    // when the large one has gone under.
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    var barBottom by remember { mutableFloatStateOf(0f) }
    var titleBottom by remember { mutableFloatStateOf(Float.MAX_VALUE) }
    val titleInBar by remember { derivedStateOf { titleBottom <= barBottom } }
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    AnimatedVisibility(titleInBar, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            EntryIcon(content.card.avatar, content.icon, 32.dp)
                            Text(content.card.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back to the list") }
                },
                actions = {
                    IconButton(onClick = onHistory) {
                        Icon(Icons.Rounded.History, contentDescription = "History", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (content.inRecycleBin) {
                        FilledTonalButton(onClick = model::restore, enabled = !model.restoring, modifier = Modifier.padding(end = 8.dp)) {
                            Icon(Icons.Rounded.RestoreFromTrash, contentDescription = null, Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text("Restore")
                        }
                    } else {
                        IconButton(onClick = onEdit) {
                            Icon(Icons.Rounded.Edit, contentDescription = "Edit", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { if (model.moveToRecycleBin()) onBack() }) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Move to the recycle bin", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                scrollBehavior = scroll,
                modifier = Modifier.onGloballyPositioned { barBottom = it.boundsInRoot().bottom },
            )
        },
        snackbarHost = { NoticeHost(snackbar, Modifier.navigationBarsPadding()) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = padding.calculateTopPadding())
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header(content, sharedKey = "$origin/$entryId", onSearch = onSearch, onTitlePlaced = { titleBottom = it })
            Banners(content)
            val wifiQrPayload = content.card.wifiQrPayload(state.reveal)
            for (section in content.card.sections) {
                CardSectionView(section, state.reveal, now, actions, wifiQrPayload)
            }
            Footer(content.card)
        }
    }
}

@Composable
private fun Header(content: CardContent, sharedKey: String, onSearch: (String) -> Unit, onTitlePlaced: (bottom: Float) -> Unit) {
    val card = content.card
    Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        EntryIcon(card.avatar, content.icon, 72.dp, Modifier.sharedIcon(sharedKey))
        Text(
            card.title,
            Modifier.sharedTitle(sharedKey).onGloballyPositioned { onTitlePlaced(it.boundsInRoot().bottom) },
            style = MaterialTheme.typography.displaySmall,
        )
        if (card.groupPath.isNotEmpty() || card.tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (card.groupPath.isNotEmpty()) {
                    Chip(
                        text = card.groupPath,
                        filled = true,
                        leading = { Icon(Icons.Rounded.Folder, contentDescription = null, Modifier.size(16.dp)) },
                        onClick = { onSearch(FilterCompletion.token(FilterKind.GROUP, card.groupName) + " ") },
                    )
                }
                for (tag in card.tags) {
                    Chip(text = "#$tag", filled = false, onClick = { onSearch(FilterCompletion.token(FilterKind.TAG, tag) + " ") })
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, filled: Boolean, onClick: () -> Unit, leading: (@Composable () -> Unit)? = null) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (filled) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        contentColor = if (filled) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        border = if (filled) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.height(32.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            leading?.invoke()
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** KeePass's own expiry, the most pressing date a field names, and the recycle bin. */
@Composable
private fun Banners(content: CardContent) {
    val card = content.card
    val dates = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())
    if (content.inRecycleBin) {
        Banner(Icons.Rounded.Delete, "In the recycle bin", problem = false)
    }
    when (val expiry = card.entryExpiry) {
        is EntryExpiry.Expired -> Banner(Icons.Rounded.ErrorOutline, "Expired on ${dates.format(expiry.at)}", problem = true)
        is EntryExpiry.Expires -> Banner(Icons.Rounded.Schedule, "Expires on ${dates.format(expiry.at)}", problem = false)
        null -> {}
    }
    card.deadline?.let { deadline ->
        val expired = deadline.hint.status == ExpiryHint.Status.EXPIRED
        Banner(Icons.Rounded.Schedule, "${deadline.label} · ${deadline.hint.text}", problem = expired, warning = !expired)
    }
}

@Composable
private fun Banner(icon: ImageVector, text: String, problem: Boolean, warning: Boolean = false) {
    val (container, onContainer) = when {
        problem -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        warning -> MaterialTheme.extraColors.warnContainer to MaterialTheme.extraColors.onWarnContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(container).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = onContainer)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = onContainer)
    }
}

@Composable
private fun Footer(card: EntryCard) {
    val dates = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())
    val parts = listOfNotNull(
        card.lastModified?.let { "Changed ${dates.format(it)}" },
        card.created?.let { "created ${dates.format(it)}" },
    )
    if (parts.isEmpty()) return
    Text(
        parts.joinToString(" · ").replaceFirstChar(Char::uppercase),
        Modifier.padding(horizontal = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The password's menu (SPEC §6.2). */
internal enum class PasswordAction { QR_CODE, LARGE_TYPE, SHARE }

/** Share… says first where the password goes, and offers the QR code, which keeps it on the phone. */
@Composable
private fun ShareWarning(onShare: () -> Unit, onShowQrCode: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.WarningAmber, contentDescription = null) },
        title = { Text("Share the password?") },
        text = {
            Text(
                "It goes to the app you choose, as plain text, and maybe to that app's cloud and history. " +
                    "To another phone, Show as QR is safer: nothing leaves this one.",
            )
        },
        confirmButton = { TextButton(onClick = onShare) { Text("Share") } },
        dismissButton = {
            Row {
                TextButton(onClick = onShowQrCode) { Text("Show as QR") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** What the rows of the card can do. */
internal class CardActions(
    val onCopy: (FieldRow) -> Unit,
    val onToggle: (String) -> Unit,
    val onToggleSection: (CardSection) -> Unit,
    val onOpenLink: (String) -> Unit,
    val onOpenAttachment: (FieldRow) -> Unit,
    val onSaveAttachment: (FieldRow) -> Unit,
    val onPasswordMenu: (PasswordAction) -> Unit,
    /** Marks a code of a list used, or not; null when the entry cannot be changed (in the recycle bin). */
    val onSetUsed: ((FieldRow, Boolean) -> Unit)? = null,
    /** The code row whose mark is being saved. */
    val marking: String? = null,
)

/** Tapping anywhere on a row copies it (SPEC §6.2). */
internal fun Modifier.copyOnTap(row: FieldRow, actions: CardActions): Modifier =
    if (row.isCopyable) clickable(onClickLabel = "Copy ${row.noticeLabel.lowercase()}") { actions.onCopy(row) } else this
