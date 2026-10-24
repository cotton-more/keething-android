package dev.keepassbar.android.entry

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.keepassbar.android.theme.MonospaceFamily
import dev.keepassbar.android.theme.extraColors
import dev.keepassbar.android.ui.CodeRing
import dev.keepassbar.android.ui.SegmentGap
import dev.keepassbar.android.ui.colouredSecret
import dev.keepassbar.android.ui.segmentShape
import dev.keepassbar.card.CardSection
import dev.keepassbar.card.EntryAvatar
import dev.keepassbar.card.ExpiryHint
import dev.keepassbar.card.FieldRow
import dev.keepassbar.card.FieldType
import dev.keepassbar.card.PaymentCard
import dev.keepassbar.card.RevealState
import dev.keepassbar.card.TotpDisplay
import java.time.Instant
import java.time.format.FormatStyle
import java.time.format.DateTimeFormatter
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilledTonalButton
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

private val OUTER = 22.dp

@Composable
internal fun CardSectionView(
    section: CardSection,
    avatar: EntryAvatar,
    reveal: RevealState,
    now: State<Instant>,
    actions: CardActions,
    wifiQrPayload: String?,
    extras: FaceExtras = FaceExtras(),
) {
    when (val kind = section.kind) {
        CardSection.Kind.Credentials -> RowGroup(section.rows) { row, shape ->
            FieldRowView(row, reveal, now, actions, shape, tile = credentialTile(row))
        }
        CardSection.Kind.Notes -> NotesView(section.rows.single(), actions)
        CardSection.Kind.Custom -> Titled(section.title) {
            RowGroup(section.rows) { row, shape -> FieldRowView(row, reveal, now, actions, shape, tile = customTile(row)) }
        }
        CardSection.Kind.Codes -> CodesView(section, reveal, actions)
        is CardSection.Kind.PaymentCard -> PaymentCardSection(section, kind.card, avatar, reveal, now, actions)
        is CardSection.Kind.WiFi -> WiFiSection(section, kind.network, wifiQrPayload, reveal, now, actions)
        is CardSection.Kind.BankAccount -> BankAccountSection(section, kind.account, reveal, now, actions)
        is CardSection.Kind.Document -> DocumentSection(section, kind.document, reveal, now, actions)
        is CardSection.Kind.Ssh -> SshSection(section, kind.login, extras.sshKey, extras.sshApp, extras.hasPassword, reveal, now, actions)
        is CardSection.Kind.Crypto -> CryptoSection(section, kind.wallet, reveal, now, actions)
        CardSection.Kind.Attachments -> RowGroup(section.rows) { row, shape -> AttachmentRowView(row, actions, shape) }
    }
}

/** What the faces show besides the entry's fields: read from attachments, or asked of the phone. */
internal data class FaceExtras(
    val sshKey: SshKeyView? = null,
    val sshApp: SshApp? = null,
    val hasPassword: Boolean = false,
)

@Composable
internal fun Titled(title: String?, content: @Composable () -> Unit) {
    Column {
        if (title != null) {
            Text(
                title,
                Modifier.padding(start = 8.dp, bottom = 8.dp),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        content()
    }
}

@Composable
internal fun RowGroup(rows: List<FieldRow>, row: @Composable (FieldRow, Shape) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(SegmentGap)) {
        rows.forEachIndexed { index, field -> row(field, segmentShape(index, rows.size, outer = OUTER)) }
    }
}

/** The tinted tile in front of a credential (SPEC §6: a tile per field kind). */
internal class Tile(val icon: ImageVector, val container: Color, val content: Color)

@Composable
private fun credentialTile(row: FieldRow): Tile? {
    val colors = MaterialTheme.colorScheme
    return when {
        row.kind == FieldRow.Kind.USERNAME -> Tile(Icons.Rounded.Person, colors.tertiaryContainer, colors.onTertiaryContainer)
        row.kind == FieldRow.Kind.PASSWORD -> Tile(Icons.Rounded.Key, colors.primaryContainer, colors.onPrimaryContainer)
        row.kind == FieldRow.Kind.TOTP -> Tile(Icons.Rounded.VerifiedUser, colors.primaryContainer, colors.onPrimaryContainer)
        row.kind == FieldRow.Kind.URL || row.link != null -> Tile(Icons.Rounded.Language, colors.secondaryContainer, colors.onSecondaryContainer)
        else -> null
    }
}

/** A custom field typed `password` has the password's key in front (Mac SPEC §3.12). */
@Composable
private fun customTile(row: FieldRow): Tile? {
    val colors = MaterialTheme.colorScheme
    return if (row.type == FieldType.PASSWORD) Tile(Icons.Rounded.Key, colors.primaryContainer, colors.onPrimaryContainer) else null
}

@Composable
private fun TileView(tile: Tile) {
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(tile.container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(tile.icon, contentDescription = null, Modifier.size(20.dp), tint = tile.content)
    }
}

@Composable
internal fun FieldRowView(
    row: FieldRow,
    reveal: RevealState,
    now: State<Instant>,
    actions: CardActions,
    shape: Shape,
    tile: Tile? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(shape)
            .background(MaterialTheme.extraColors.row)
            .copyOnTap(row, actions)
            .padding(start = if (tile != null) 14.dp else 18.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tile?.let {
            TileView(it)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            if (row.label.isNotEmpty()) {
                Text(row.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (row.kind == FieldRow.Kind.TOTP) {
                row.totp?.let { CodeValue(TotpDisplay.of(it, now.value)) }
            } else {
                FieldValueText(row, reveal)
            }
            row.expiry?.let { hint ->
                Text(
                    hint.text,
                    style = MaterialTheme.typography.labelLarge,
                    color = when (hint.status) {
                        ExpiryHint.Status.EXPIRED -> MaterialTheme.colorScheme.error
                        ExpiryHint.Status.SOON -> MaterialTheme.extraColors.warn
                        ExpiryHint.Status.VALID -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        if (row.isConcealed && !row.isEmpty) {
            val shown = reveal.isRevealed(row.id)
            IconButton(onClick = { actions.onToggle(row.id) }) {
                Icon(
                    if (shown) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = if (shown) "Hide ${row.noticeLabel.lowercase()}" else "Show ${row.noticeLabel.lowercase()}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        row.link?.let { url ->
            IconButton(onClick = { actions.onOpenLink(url) }) {
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = "Open in the browser", tint = MaterialTheme.colorScheme.primary)
            }
        }
        // Every row that copies on a tap says so with the same button.
        if (row.isCopyable) {
            IconButton(onClick = { actions.onCopy(row) }) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy ${row.noticeLabel.lowercase()}", tint = MaterialTheme.colorScheme.primary)
            }
        }
        if (row.kind == FieldRow.Kind.PASSWORD && !row.isEmpty) PasswordMenu(actions)
    }
}

/** Show as QR, Large type, Share… (SPEC §6.2). */
@Composable
private fun PasswordMenu(actions: CardActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Rounded.MoreVert, contentDescription = "More for the password", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val item: @Composable (String, ImageVector, PasswordAction) -> Unit = { text, icon, action ->
                DropdownMenuItem(
                    text = { Text(text) },
                    leadingIcon = { Icon(icon, contentDescription = null) },
                    onClick = {
                        open = false
                        actions.onPasswordMenu(action)
                    },
                )
            }
            item("Show as QR", Icons.Rounded.QrCode2, PasswordAction.QR_CODE)
            item("Large type", Icons.Rounded.TextFields, PasswordAction.LARGE_TYPE)
            item("Share…", Icons.Rounded.Share, PasswordAction.SHARE)
        }
    }
}

/** A value as the card shows it: masked, revealed (monospaced, digits and symbols coloured), a link, or text. */
@Composable
private fun FieldValueText(row: FieldRow, reveal: RevealState) {
    val text = reveal.displayValue(row)
    val style = MaterialTheme.typography.bodyLarge
    when {
        row.isConcealed && !reveal.isRevealed(row.id) -> Text(text, style = style, letterSpacing = 2.sp, maxLines = 1)
        row.isConcealed -> Text(colouredSecret(text), style = style, fontFamily = MonospaceFamily)
        row.link != null -> Text(text, style = style, color = MaterialTheme.colorScheme.primary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        else -> Text(text, style = style)
    }
}

/** A live code: the digits, the ring and the seconds left; red in its last five seconds. */
@Composable
private fun CodeValue(display: TotpDisplay) {
    val colour = if (display.secondsRemaining <= 5) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            display.formatted,
            fontFamily = MonospaceFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 26.sp,
            letterSpacing = 1.sp,
            color = colour,
        )
        CodeRing(display.period, colour, MaterialTheme.colorScheme.outlineVariant, size = 22.dp)
        Text("${display.secondsRemaining} s", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NotesView(row: FieldRow, actions: CardActions) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(OUTER)).background(MaterialTheme.extraColors.row).padding(start = 18.dp, end = 6.dp, bottom = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Notes", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = { actions.onCopy(row) }) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy the notes", tint = MaterialTheme.colorScheme.primary)
            }
        }
        // Selectable rather than tap-to-copy: notes are long, a part of them is what is wanted.
        SelectionContainer(Modifier.padding(end = 12.dp)) {
            Text(row.revealedText(), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/**
 * A list of codes (recovery codes): a numbered grid with one eye for all of them (SPEC §6). A code marked used is
 * struck through with its day; the title and a bar say how many are left, the next unused code is outlined and one
 * button copies it (its notice offers Mark as used); a long press marks a code used or not.
 */
@Composable
private fun CodesView(section: CardSection, reveal: RevealState, actions: CardActions) {
    val concealed = section.rows.filter { it.isConcealed && !it.isEmpty }
    val shown = concealed.isNotEmpty() && concealed.all { reveal.isRevealed(it.id) }
    val used = section.rows.count { it.usedOn != null }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(OUTER)).background(MaterialTheme.extraColors.row).padding(start = 18.dp, end = 6.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                Text(section.title.orEmpty(), style = MaterialTheme.typography.titleSmall)
                Text(
                    "${section.rows.size - used} of ${section.rows.size} left",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (used == section.rows.size) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (concealed.isNotEmpty()) {
                IconButton(onClick = { actions.onToggleSection(section) }) {
                    Icon(
                        if (shown) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = if (shown) "Hide the codes" else "Show the codes",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        LeftBar(section.rows, Modifier.padding(end = 12.dp, bottom = 4.dp))
        val next = section.rows.firstOrNull { it.usedOn == null && it.isCopyable }
        for (pair in section.rows.chunked(2)) {
            Row(Modifier.padding(end = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (row in pair) CodeCell(row, reveal, actions, Modifier.weight(1f), isNext = row.id == next?.id)
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        FilledTonalButton(
            onClick = { next?.let(actions.onCopy) },
            enabled = next != null,
            modifier = Modifier.fillMaxWidth().padding(end = 12.dp, top = 6.dp).heightIn(min = 48.dp),
        ) {
            Icon(Icons.Rounded.ContentCopy, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(if (next == null) "No unused codes left" else "Copy next unused · ${next.label}", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** How many codes are left: one segment per code, on one line however many (thinner for more), used ones empty. */
@Composable
private fun LeftBar(rows: List<FieldRow>, modifier: Modifier) {
    val left = rows.count { it.usedOn == null }
    val filled = if (left <= 2) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.outlineVariant
    val gap = if (rows.size > 16) 2.dp else 3.dp
    Row(
        modifier.fillMaxWidth().height(6.dp).semantics { contentDescription = "$left of ${rows.size} left" },
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        val shape = RoundedCornerShape(if (rows.size > 30) 1.dp else 3.dp)
        for (row in rows) Box(Modifier.weight(1f).fillMaxHeight().clip(shape).background(if (row.usedOn == null) filled else empty))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CodeCell(row: FieldRow, reveal: RevealState, actions: CardActions, modifier: Modifier, isNext: Boolean = false) {
    var menu by remember { mutableStateOf(false) }
    val setUsed = actions.onSetUsed
    val saving = actions.marking == row.id
    val usedOn = row.usedOn
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .then(if (isNext) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else Modifier)
                .combinedClickable(
                    enabled = row.isCopyable,
                    onClickLabel = "Copy ${row.noticeLabel.lowercase()}",
                    onClick = { actions.onCopy(row) },
                    onLongClickLabel = if (usedOn == null) "Mark as used" else "Mark as not used",
                    onLongClick = if (setUsed != null && actions.marking == null) ({ menu = true }) else null,
                )
                .heightIn(min = 40.dp)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(row.label, Modifier.width(24.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                val text = reveal.displayValue(row)
                val masked = row.isConcealed && !reveal.isRevealed(row.id)
                // A used code masked is only its mark; shown, it is struck through above it.
                if (usedOn == null || !masked) {
                    Text(
                        if (row.isConcealed && !masked && usedOn == null) colouredSecret(text) else AnnotatedString(text),
                        fontFamily = MonospaceFamily,
                        style = MaterialTheme.typography.bodyMedium.copy(textDecoration = if (usedOn != null) TextDecoration.LineThrough else null),
                        color = if (usedOn != null) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (usedOn != null) {
                    Text(
                        "used " + USED_DAY.format(usedOn),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            if (saving) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        }
        if (setUsed != null) {
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(if (usedOn == null) "Mark as used" else "Mark as not used") },
                    onClick = {
                        menu = false
                        setUsed(row, usedOn == null)
                    },
                )
            }
        }
    }
}

private val USED_DAY: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

/**
 * An attachment: its name and size, opened in another app or saved where the user says. One whose content
 * the file does not have (no size) offers neither.
 */
@Composable
private fun AttachmentRowView(row: FieldRow, actions: CardActions, shape: Shape) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(shape)
            .background(MaterialTheme.extraColors.row)
            .padding(start = 14.dp, end = if (row.size != null) 6.dp else 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val colors = MaterialTheme.colorScheme
        TileView(Tile(Icons.Rounded.AttachFile, colors.secondaryContainer, colors.onSecondaryContainer))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(row.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            row.size?.let {
                Text(Formatter.formatShortFileSize(context, it.toLong()), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
        if (row.size != null) {
            IconButton(onClick = { actions.onOpenAttachment(row) }) {
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = "Open ${row.label} in another app", tint = colors.primary)
            }
            IconButton(onClick = { actions.onSaveAttachment(row) }) {
                Icon(Icons.Rounded.Download, contentDescription = "Save ${row.label} to a folder", tint = colors.primary)
            }
        }
    }
}
