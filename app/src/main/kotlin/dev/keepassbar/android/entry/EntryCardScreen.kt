package dev.keepassbar.android.entry

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
 * Editing and the password and code menus come with later steps.
 */
@Composable
internal fun EntryCardScreen(
    container: AppContainer,
    database: DatabaseSession,
    entryId: String,
    origin: String,
    onBack: () -> Unit,
    onSearch: (String) -> Unit,
) {
    val model = viewModel {
        EntryCardViewModel(entryId, database, container.recents, container.clipboard, container.attachmentFiles, container.androidAttachments)
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
    val actions = CardActions(
        onCopy = model::copy,
        onToggle = model::toggleReveal,
        onToggleSection = model::toggleSection,
        onOpenLink = openLink,
        onOpenAttachment = model::openAttachment,
        onSaveAttachment = { row -> saveDialog.launch(model.saveRequest(row)) },
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Row(Modifier.statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp)) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back to the list") }
            }
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
            Header(content, sharedKey = "$origin/$entryId", onSearch = onSearch)
            Banners(content)
            for (section in content.card.sections) {
                CardSectionView(section, state.reveal, now, actions)
            }
            Footer(content.card)
        }
    }
}

@Composable
private fun Header(content: CardContent, sharedKey: String, onSearch: (String) -> Unit) {
    val card = content.card
    Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        EntryIcon(card.avatar, content.icon, 72.dp, Modifier.sharedIcon(sharedKey))
        Text(card.title, Modifier.sharedTitle(sharedKey), style = MaterialTheme.typography.displaySmall)
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

/** What the rows of the card can do. */
internal class CardActions(
    val onCopy: (FieldRow) -> Unit,
    val onToggle: (String) -> Unit,
    val onToggleSection: (CardSection) -> Unit,
    val onOpenLink: (String) -> Unit,
    val onOpenAttachment: (FieldRow) -> Unit,
    val onSaveAttachment: (FieldRow) -> Unit,
)

/** Tapping anywhere on a row copies it (SPEC §6.2). */
internal fun Modifier.copyOnTap(row: FieldRow, actions: CardActions): Modifier =
    if (row.isCopyable) clickable(onClickLabel = "Copy ${row.noticeLabel.lowercase()}") { actions.onCopy(row) } else this
