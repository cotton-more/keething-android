package dev.keepassbar.android.entry

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Brush
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
import dev.keepassbar.android.ui.QrCodeImage
import dev.keepassbar.android.ui.SegmentGap
import dev.keepassbar.android.ui.colouredSecret
import dev.keepassbar.android.ui.segmentShape
import dev.keepassbar.card.CardSection
import dev.keepassbar.card.ExpiryHint
import dev.keepassbar.card.FieldRow
import dev.keepassbar.card.PaymentCard
import dev.keepassbar.card.RevealState
import dev.keepassbar.card.TotpDisplay
import dev.keepassbar.card.WiFiNetwork
import java.time.Instant

private val OUTER = 22.dp

@Composable
internal fun CardSectionView(section: CardSection, reveal: RevealState, now: State<Instant>, actions: CardActions, wifiQrPayload: String?) {
    when (val kind = section.kind) {
        CardSection.Kind.Credentials -> RowGroup(section.rows) { row, shape ->
            FieldRowView(row, reveal, now, actions, shape, tile = credentialTile(row), copyButton = true)
        }
        CardSection.Kind.Notes -> NotesView(section.rows.single(), actions)
        CardSection.Kind.Custom -> Titled(section.title) {
            RowGroup(section.rows) { row, shape -> FieldRowView(row, reveal, now, actions, shape) }
        }
        CardSection.Kind.Codes -> CodesView(section, reveal, actions)
        is CardSection.Kind.PaymentCard -> Titled(section.title ?: "Payment card") {
            PaymentCardView(kind.card, section.rows.firstOrNull { it.id == kind.card.numberRowId }, reveal, actions)
            Spacer(Modifier.size(8.dp))
            RowGroup(section.rows) { row, shape -> FieldRowView(row, reveal, now, actions, shape) }
        }
        is CardSection.Kind.WiFi -> Titled(section.title ?: "Wi-Fi") {
            WiFiQrView(kind.network, wifiQrPayload, actions)
            Spacer(Modifier.size(8.dp))
            RowGroup(section.rows) { row, shape -> FieldRowView(row, reveal, now, actions, shape) }
        }
        CardSection.Kind.Attachments -> RowGroup(section.rows) { row, shape -> AttachmentRowView(row, actions, shape) }
    }
}

@Composable
private fun Titled(title: String?, content: @Composable () -> Unit) {
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
private fun RowGroup(rows: List<FieldRow>, row: @Composable (FieldRow, Shape) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(SegmentGap)) {
        rows.forEachIndexed { index, field -> row(field, segmentShape(index, rows.size, outer = OUTER)) }
    }
}

/** The tinted tile in front of a credential (SPEC §6: a tile per field kind). */
private class Tile(val icon: ImageVector, val container: Color, val content: Color)

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
private fun FieldRowView(
    row: FieldRow,
    reveal: RevealState,
    now: State<Instant>,
    actions: CardActions,
    shape: Shape,
    tile: Tile? = null,
    copyButton: Boolean = false,
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
        if (copyButton && row.isCopyable) {
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

/**
 * A Wi-Fi network's QR code for a phone to join (SPEC §3.13; Mac `WiFiSectionView`). It holds the password,
 * so it is drawn only while the password row is revealed: "Show QR code" reveals it, "Hide" masks it again.
 */
@Composable
private fun WiFiQrView(network: WiFiNetwork, payload: String?, actions: CardActions) {
    val rowId = network.passwordRowId
    if (payload == null && rowId == null) return
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(OUTER)).background(MaterialTheme.extraColors.row).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (payload != null) {
            QrCodeImage(payload, description = "QR code to join ${network.ssid}", modifier = Modifier.size(148.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Join ${network.ssid}", style = MaterialTheme.typography.titleSmall)
                Text("Point the other phone's camera at the code.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (rowId != null) {
                    TextButton(onClick = { actions.onToggle(rowId) }, contentPadding = ButtonDefaults.TextButtonWithIconContentPadding) { Text("Hide") }
                }
            }
        } else if (rowId != null) {
            TileView(Tile(Icons.Rounded.QrCode2, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer))
            Column(Modifier.weight(1f)) {
                Text("Join ${network.ssid} with a phone", style = MaterialTheme.typography.titleSmall)
                Text("The code contains the password.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledTonalButton(onClick = { actions.onToggle(rowId) }) { Text("Show QR code") }
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

/** A list of codes (recovery codes): a numbered grid with one eye for all of them (SPEC §6). */
@Composable
private fun CodesView(section: CardSection, reveal: RevealState, actions: CardActions) {
    val concealed = section.rows.filter { it.isConcealed && !it.isEmpty }
    val shown = concealed.isNotEmpty() && concealed.all { reveal.isRevealed(it.id) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(OUTER)).background(MaterialTheme.extraColors.row).padding(start = 18.dp, end = 6.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(section.title.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
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
        for (pair in section.rows.chunked(2)) {
            Row(Modifier.padding(end = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (row in pair) {
                    Surface(
                        onClick = { actions.onCopy(row) },
                        enabled = row.isCopyable,
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.weight(1f),
                    ) {
                        Row(Modifier.heightIn(min = 40.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(row.label, Modifier.width(24.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val text = reveal.displayValue(row)
                            Text(
                                if (row.isConcealed && reveal.isRevealed(row.id)) colouredSecret(text) else AnnotatedString(text),
                                fontFamily = MonospaceFamily,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** The payment card drawn as a card: brand, number (masked down to its last four digits), holder, expiry. */
@Composable
private fun PaymentCardView(card: PaymentCard, numberRow: FieldRow?, reveal: RevealState, actions: CardActions) {
    val colors = MaterialTheme.colorScheme
    val revealed = reveal.isRevealed(card.numberRowId)
    // A card reads as a dark object in both themes: the strong tones in light, the container tones in dark.
    val dark = isSystemInDarkTheme()
    val (from, to) = if (dark) colors.primaryContainer to colors.tertiaryContainer else colors.primary to colors.tertiary
    val onCard = if (dark) colors.onPrimaryContainer else colors.onPrimary
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1.586f)
            .clip(RoundedCornerShape(OUTER))
            .background(Brush.linearGradient(listOf(from, to)))
            .then(if (numberRow != null) Modifier.copyOnTap(numberRow, actions) else Modifier)
            .padding(20.dp),
    ) {
        Text(card.brand.orEmpty(), Modifier.align(Alignment.TopEnd), color = onCard, style = MaterialTheme.typography.titleMedium)
        Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (revealed) card.groupedNumber ?: numberRow?.revealedText().orEmpty() else card.maskedNumber,
                color = onCard,
                fontFamily = MonospaceFamily,
                fontSize = 21.sp,
                letterSpacing = 1.sp,
            )
            if (numberRow != null) {
                IconButton(onClick = { actions.onToggle(card.numberRowId) }) {
                    Icon(
                        if (revealed) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = if (revealed) "Hide the card number" else "Show the card number",
                        tint = onCard,
                    )
                }
            }
        }
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(card.holder.orEmpty(), Modifier.weight(1f), color = onCard, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            card.expiry?.let { expiry ->
                Text(
                    "%02d/%02d".format(expiry.date.month, expiry.date.year % 100),
                    color = if (card.expiryHint?.status == ExpiryHint.Status.EXPIRED) colors.errorContainer else onCard,
                    fontFamily = MonospaceFamily,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

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
