package dev.keething.android.entry

import dev.keething.android.ui.resolve
import android.text.format.Formatter
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.InstallMobile
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.keething.android.R
import dev.keething.android.theme.MonospaceFamily
import dev.keething.android.ui.uiText
import dev.keething.android.theme.extraColors
import dev.keething.android.ui.CodeValue
import dev.keething.android.ui.SegmentGap
import dev.keething.android.ui.colouredSecret
import dev.keething.android.ui.segmentShape
import dev.keething.android.ui.spokenMasked
import dev.keething.card.CardSection
import dev.keething.card.EntryAvatar
import dev.keething.card.ExpiryHint
import dev.keething.card.FieldRow
import dev.keething.card.FieldType
import dev.keething.card.PaymentCard
import dev.keething.card.RevealState
import dev.keething.card.TotpDisplay
import dev.keething.card.ValueStyle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
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
        CardSection.Kind.Note, CardSection.Kind.Notes -> NoteTextView(section.rows.single(), reveal, actions)
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
                Modifier.padding(start = 8.dp, bottom = 8.dp).semantics { heading() },
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
        // A code that cannot be used: the same tile, grey (agreed on the canvas "Code that cannot be used", variant A).
        row.kind == FieldRow.Kind.TOTP && row.totp == null -> Tile(Icons.Rounded.VerifiedUser, colors.surfaceContainerHighest, colors.onSurfaceVariant)
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

/**
 * A row's first line: a one-line field's height (its name and value), which a button fills. A row's tile and buttons
 * stay in it, at the top, and a value shown over several lines grows the row below them (keething-spec
 * plans/done/android-notes.md AN5); a value shorter than it is centred in it, as before.
 */
private val FIRST_LINE = 48.dp

/** [content] centred in a row's [FIRST_LINE]. */
@Composable
private fun InFirstLine(content: @Composable () -> Unit) {
    Box(Modifier.height(FIRST_LINE), contentAlignment = Alignment.Center) { content() }
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
    row.app?.let { app ->
        AppRowView(row, app, actions, shape)
        return
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(shape)
            .background(MaterialTheme.extraColors.row)
            .copyOnTap(row, actions)
            .padding(start = if (tile != null) 14.dp else 18.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        tile?.let {
            InFirstLine { TileView(it) }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f).heightIn(min = FIRST_LINE), verticalArrangement = Arrangement.Center) {
            if (row.label.resolve().isNotEmpty()) {
                Text(row.label.resolve(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (row.kind == FieldRow.Kind.TOTP) {
                row.totp?.let { CodeValue(TotpDisplay.of(it, now.value), onCopyNext = { actions.onCopyNext(row) }) }
                row.codeProblem?.let { Text(it.resolve(), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                FieldValueText(row, reveal)
            }
            row.expiry?.let { hint ->
                Text(
                    hint.text.resolve(),
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
                    contentDescription = stringResource(if (shown) R.string.entry_row_hide else R.string.entry_row_show, row.noticeLabel.resolve().lowercase()),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        row.link?.let { url ->
            IconButton(onClick = { actions.onOpenLink(url) }) {
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = stringResource(R.string.entry_row_open_link), tint = MaterialTheme.colorScheme.primary)
            }
        }
        // Every row that copies on a tap says so with the same button.
        if (row.isCopyable) {
            IconButton(onClick = { actions.onCopy(row) }) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.entry_row_copy, row.noticeLabel.resolve().lowercase()), tint = MaterialTheme.colorScheme.primary)
            }
        }
        if (row.kind == FieldRow.Kind.PASSWORD && !row.isEmpty) PasswordMenu(actions)
    }
}

/**
 * An app's address shown as the app (keething-spec card.app-address): installed, its icon, its name and an open
 * button; missing, the default app icon, the package and a store button. A tap copies the address as stored. Read
 * again whenever the card comes back, so an app installed from the store shows at once.
 */
@Composable
private fun AppRowView(row: FieldRow, app: ValueStyle.App, actions: CardActions, shape: Shape) {
    val context = LocalContext.current
    val iconSize = with(LocalDensity.current) { 40.dp.roundToPx() }
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumed++ }
    val onPhone = remember(app.packageName, resumed) { AppOnPhone.of(context, app.packageName, iconSize) }
    val installed = onPhone as? AppOnPhone.Installed
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(shape)
            .background(MaterialTheme.extraColors.row)
            .copyOnTap(row, actions)
            .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        InFirstLine {
            if (installed != null) {
                Image(installed.icon, contentDescription = null, Modifier.size(40.dp))
            } else {
                val colors = MaterialTheme.colorScheme
                TileView(Tile(Icons.Rounded.Android, colors.secondaryContainer, colors.onSecondaryContainer))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).heightIn(min = FIRST_LINE), verticalArrangement = Arrangement.Center) {
            app.fieldName?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(
                installed?.name ?: app.packageName,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = if (installed == null) MonospaceFamily else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                row.storedText().trim(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = MonospaceFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when (onPhone) {
            is AppOnPhone.Installed -> onPhone.launch?.let { launch ->
                IconButton(onClick = { actions.onStart(launch, uiText(R.string.entry_app_could_not_open, onPhone.name)) }) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = stringResource(R.string.entry_app_open, onPhone.name), tint = MaterialTheme.colorScheme.primary)
                }
            }
            is AppOnPhone.Missing -> when (val store = onPhone.store) {
                is AppOnPhone.Store.One -> IconButton(onClick = { actions.onStart(store.intent, uiText(R.string.entry_app_could_not_open, store.name)) }) {
                    Image(store.icon, contentDescription = stringResource(R.string.entry_app_find_in, store.name), Modifier.size(24.dp))
                }
                is AppOnPhone.Store.Several -> IconButton(onClick = { actions.onStart(store.intent, uiText(R.string.entry_app_no_store)) }) {
                    Icon(Icons.Rounded.InstallMobile, contentDescription = stringResource(R.string.entry_app_find_in_store), tint = MaterialTheme.colorScheme.primary)
                }
                null -> Unit
            }
        }
        if (row.isCopyable) {
            IconButton(onClick = { actions.onCopy(row) }) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.entry_row_copy, row.noticeLabel.resolve().lowercase()), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** Show as QR, Large type, Share… (SPEC §6.2). */
@Composable
private fun PasswordMenu(actions: CardActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.entry_password_more), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val item: @Composable (Int, ImageVector, PasswordAction) -> Unit = { text, icon, action ->
                DropdownMenuItem(
                    text = { Text(stringResource(text)) },
                    leadingIcon = { Icon(icon, contentDescription = null) },
                    onClick = {
                        open = false
                        actions.onPasswordMenu(action)
                    },
                )
            }
            item(R.string.entry_password_qr, Icons.Rounded.QrCode2, PasswordAction.QR_CODE)
            item(R.string.entry_password_large, Icons.Rounded.TextFields, PasswordAction.LARGE_TYPE)
            item(R.string.entry_password_share, Icons.Rounded.Share, PasswordAction.SHARE)
        }
    }
}

/**
 * A value as the card shows it: masked, revealed (monospaced, digits and symbols coloured), a link, or text; an empty
 * one as "—" in the secondary colour, said "empty" (keething-spec card.fields AC2).
 */
@Composable
private fun FieldValueText(row: FieldRow, reveal: RevealState) {
    val text = reveal.displayValue(row)
    val style = MaterialTheme.typography.bodyLarge
    when {
        row.isEmpty -> {
            val empty = stringResource(R.string.entry_row_empty)
            Text("—", Modifier.semantics { contentDescription = empty }, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant) // not user text
        }
        row.isConcealed && !reveal.isRevealed(row.id) -> Text(text, Modifier.spokenMasked(text), style = style, letterSpacing = 2.sp, maxLines = 1)
        row.isConcealed -> Text(colouredSecret(text), style = style, fontFamily = MonospaceFamily)
        row.link != null -> Text(text, style = style, color = MaterialTheme.colorScheme.primary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        else -> Text(text, style = style)
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
    val left = section.codesLeft
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(OUTER)).background(MaterialTheme.extraColors.row).padding(start = 18.dp, end = 6.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                Text(section.title.orEmpty(), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
                Text(
                    pluralStringResource(R.plurals.entry_codes_left, left, left, section.rows.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (left == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (concealed.isNotEmpty()) {
                IconButton(onClick = { actions.onToggleSection(section) }) {
                    Icon(
                        if (shown) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = stringResource(if (shown) R.string.entry_codes_hide else R.string.entry_codes_show),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        LeftBar(section, Modifier.padding(end = 12.dp, bottom = 4.dp))
        val next = section.nextUnusedCode
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
            Text(if (next == null) stringResource(R.string.entry_codes_none_left) else stringResource(R.string.entry_codes_copy_next, next.label.resolve()), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** How many codes are left: one segment per code, on one line however many (thinner for more), used and empty ones empty. */
@Composable
private fun LeftBar(section: CardSection, modifier: Modifier) {
    val rows = section.rows
    val left = section.codesLeft
    val filled = if (section.fewCodesLeft) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.outlineVariant
    val gap = if (rows.size > 16) 2.dp else 3.dp
    val spoken = pluralStringResource(R.plurals.entry_codes_left, left, left, rows.size)
    Row(
        modifier.fillMaxWidth().height(6.dp).semantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        val shape = RoundedCornerShape(if (rows.size > 30) 1.dp else 3.dp)
        for (row in rows) Box(Modifier.weight(1f).fillMaxHeight().clip(shape).background(if (section.isLeft(row)) filled else empty))
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
                    onClickLabel = stringResource(R.string.entry_row_copy, row.noticeLabel.resolve().lowercase()),
                    onClick = { actions.onCopy(row) },
                    onLongClickLabel = stringResource(if (usedOn == null) R.string.entry_codes_mark_used else R.string.entry_codes_mark_not_used),
                    onLongClick = if (setUsed != null && actions.marking == null) ({ menu = true }) else null,
                )
                .heightIn(min = 40.dp)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(row.label.resolve(), Modifier.width(24.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                val text = reveal.displayValue(row)
                val masked = row.isConcealed && !reveal.isRevealed(row.id)
                // A used code masked is only its mark; shown, it is struck through above it.
                if (usedOn == null || !masked) {
                    Text(
                        if (row.isConcealed && !masked && usedOn == null) colouredSecret(text) else AnnotatedString(text),
                        if (masked) Modifier.spokenMasked(text) else Modifier,
                        fontFamily = MonospaceFamily,
                        style = MaterialTheme.typography.bodyMedium.copy(textDecoration = if (usedOn != null) TextDecoration.LineThrough else null),
                        color = if (usedOn != null) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (usedOn != null) {
                    Text(
                        stringResource(R.string.entry_codes_used_on, USED_DAY.format(usedOn)),
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
                    text = { Text(stringResource(if (usedOn == null) R.string.entry_codes_mark_used else R.string.entry_codes_mark_not_used)) },
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
        verticalAlignment = Alignment.Top,
    ) {
        val colors = MaterialTheme.colorScheme
        InFirstLine { TileView(Tile(Icons.Rounded.AttachFile, colors.secondaryContainer, colors.onSecondaryContainer)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).heightIn(min = FIRST_LINE), verticalArrangement = Arrangement.Center) {
            Text(row.label.resolve(), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            row.size?.let {
                Text(Formatter.formatShortFileSize(context, it.toLong()), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
        if (row.size != null) {
            IconButton(onClick = { actions.onOpenAttachment(row) }) {
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = stringResource(R.string.entry_attachment_open, row.label.resolve()), tint = colors.primary)
            }
            IconButton(onClick = { actions.onSaveAttachment(row) }) {
                Icon(Icons.Rounded.Download, contentDescription = stringResource(R.string.entry_attachment_save, row.label.resolve()), tint = colors.primary)
            }
        }
    }
}
