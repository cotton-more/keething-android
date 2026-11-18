package dev.keething.android.entry

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import dev.keething.android.R
import dev.keething.android.theme.MonospaceFamily
import dev.keething.android.ui.resolve
import dev.keething.android.theme.extraColors
import dev.keething.android.ui.spokenMasked
import dev.keething.card.BankAccount
import dev.keething.card.CardSection
import dev.keething.card.Countries
import dev.keething.card.ExpiryHint
import dev.keething.card.FieldDate
import dev.keething.card.FieldRow
import dev.keething.card.IdentityDocument
import dev.keething.card.RevealState
import dev.keething.card.RowIds
import dev.keething.card.SshKeyLocation
import dev.keething.card.SshLogin
import java.time.Instant
import java.util.Locale
import kotlin.math.sin

/**
 * A bank account as a label like the Wi-Fi network's (SPEC §6.2): the holder, the number grouped (an IBAN with its
 * country and check digits in the accent), the BIC or БИК, a correspondent account, the bank; a tap copies a line, a
 * number without its spaces. "Copy" copies the number, "Share" the details as text. Under it only what it does not show.
 */
@Composable
internal fun BankAccountSection(section: CardSection, account: BankAccount, reveal: RevealState, now: State<Instant>, actions: CardActions) {
    val colors = MaterialTheme.extraColors
    val rows = remember(section) { section.rows.associateBy { it.id } }
    val number = rows[account.numberRowId]
    val concealed = number != null && number.isConcealed && !number.isEmpty
    val shown = !concealed || reveal.isRevealed(account.numberRowId)
    Titled(section.title) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FaceWidth {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(colors.sticker)
                        .border(1.dp, colors.stickerEdge, RoundedCornerShape(22.dp))
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FaceTile(Icons.Rounded.AccountBalance)
                        Text(stringResource(R.string.entry_bank_heading), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, letterSpacing = 1.4.sp, color = colors.stickerLabel, maxLines = 1)
                        CountryChip(account.country)
                    }
                    account.holderRowId?.let { id ->
                        StickerLine(stringResource(R.string.entry_bank_holder), rows[id], actions) {
                            Text(account.holder.orEmpty(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    StickerLine(account.numberLabel, number, actions) {
                        val text = if (shown) account.numberShown ?: number?.let(reveal::displayValue).orEmpty() else RevealState.MASK
                        val accent = MaterialTheme.extraColors.digit
                        // One line, never wrapped (keething-spec types.bank-account AC2): smaller first, down to 13 sp,
                        // where the longest IBAN (34 characters, 42 grouped) fits a phone's face; then cut with "…".
                        Text(
                            buildAnnotatedString {
                                val groups = text.split(' ')
                                groups.forEachIndexed { i, group ->
                                    if (i > 0) append(' ')
                                    // An IBAN's country and check digits in the accent; not a whole ungrouped value that is no IBAN.
                                    if (i == 0 && groups.size > 1 && shown && account.numberLabel == "IBAN") withStyle(SpanStyle(color = accent)) { append(group) } else append(group)
                                }
                            },
                            if (shown) Modifier else Modifier.spokenMasked(text),
                            style = MaterialTheme.typography.titleLarge,
                            fontFamily = MonospaceFamily,
                            letterSpacing = 0.5.sp,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            autoSize = TextAutoSize.StepBased(minFontSize = 13.sp, maxFontSize = MaterialTheme.typography.titleLarge.fontSize, stepSize = 0.5.sp),
                        )
                    }
                    if (account.items.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            for (item in account.items) {
                                FaceItem(item.label, rows[item.rowId], actions) {
                                    Text(item.value, style = MaterialTheme.typography.bodyLarge, fontFamily = if (item.monospace) MonospaceFamily else null)
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = colors.stickerEdge)
                    Row(Modifier.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.entry_bank_tap_line), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = colors.stickerLabel)
                        if (concealed) ShowButton(shown) { actions.onToggle(account.numberRowId) }
                    }
                }
                account.problem?.let {
                    Note(Icons.Rounded.ErrorOutline, it, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (number != null && !number.isEmpty) {
                        Button(onClick = { actions.onCopy(number) }, Modifier.weight(1f).heightIn(min = 52.dp)) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                            Text(stringResource(if (account.numberLabel == "IBAN") R.string.entry_bank_copy_iban else R.string.entry_bank_copy_account), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (account.shareText() != null) {
                        FilledTonalButton(onClick = actions.onShareBank, Modifier.heightIn(min = 52.dp)) {
                            Icon(Icons.Rounded.Share, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                            Text(stringResource(R.string.entry_bank_share), maxLines = 1)
                        }
                    }
                }
            }
            val others = section.rows.filter { it.id !in account.faceRowIds }
            if (others.isNotEmpty()) RowGroup(others) { row, shape -> FieldRowView(row, reveal, now, actions, shape) }
        }
    }
}

/**
 * An identity document drawn as one (SPEC §6.2): paper with a guilloche, its type and the issuing country's flag, the
 * name, the number, the dates. No photo: such entries keep a scan of the whole document, not the portrait. A tap on a
 * line copies it; the expiry is a chip under it when soon or past.
 */
@Composable
internal fun DocumentSection(section: CardSection, document: IdentityDocument, reveal: RevealState, now: State<Instant>, actions: CardActions) {
    val colors = MaterialTheme.extraColors
    val ink = Color(0xFF14171C)
    val rows = remember(section) { section.rows.associateBy { it.id } }
    val number = rows[document.numberRowId]
    val concealed = number != null && number.isConcealed && !number.isEmpty
    val shown = !concealed || reveal.isRevealed(document.numberRowId)
    val locale = Locale.getDefault()
    Titled(section.title) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FaceWidth {
                BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val width = minOf(maxWidth, FACE_MAX_WIDTH)
                    val shape = RoundedCornerShape(18.dp)
                    Box(
                        Modifier
                            .width(width)
                            // ID-1 at least; taller when large text needs it.
                            .heightIn(min = width / 1.586f)
                            .shadow(8.dp, shape)
                            .clip(shape)
                            .background(colors.paper)
                            .border(1.dp, colors.paperEdge, shape),
                    ) {
                        Guilloche(colors.paperWave, Modifier.matchParentSize())
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(document.heading(locale), Modifier.weight(1f), color = ink, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.6.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                document.country?.let { country ->
                                    Text(
                                        listOf(document.countryCode?.let(Countries::flag) ?: Countries.UNKNOWN_FLAG, country.uppercase(locale)).joinToString(" "),
                                        Modifier.widthIn(max = width * 0.5f),
                                        color = ink,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            document.nameRowId?.let { id ->
                                PaperLine(stringResource(R.string.entry_document_name), rows[id], actions) { Text(document.name.orEmpty(), color = ink, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                            }
                            PaperLine(stringResource(R.string.entry_document_number), number, actions) {
                                Text(
                                    if (shown) number?.revealedText().orEmpty() else RevealState.MASK,
                                    if (shown) Modifier else Modifier.spokenMasked(RevealState.MASK),
                                    color = ink,
                                    fontFamily = MonospaceFamily,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 1.sp,
                                )
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                DateLine(stringResource(R.string.entry_document_born), document.birth, document.birthRowId, rows, actions, ink, locale)
                                DateLine(stringResource(R.string.entry_document_issued), document.issued, document.issuedRowId, rows, actions, ink, locale)
                                val expired = document.expiryHint?.status == ExpiryHint.Status.EXPIRED
                                DateLine(stringResource(R.string.entry_document_expires), document.expiry, document.expiryRowId, rows, actions, if (expired) MaterialTheme.colorScheme.error else ink, locale)
                            }
                        }
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    if (concealed) {
                        FilledTonalButton(onClick = { actions.onToggle(document.numberRowId) }, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                            Icon(if (shown) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                            Text(stringResource(if (shown) R.string.entry_face_hide else R.string.entry_face_show))
                        }
                    }
                    document.expiryHint?.takeIf { it.status != ExpiryHint.Status.VALID }?.let { ExpiryChip(it) }
                    Text(stringResource(R.string.entry_face_tap_line), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val others = section.rows.filter { it.id !in document.faceRowIds }
            if (others.isNotEmpty()) RowGroup(others) { row, shape -> FieldRowView(row, reveal, now, actions, shape) }
        }
    }
}

@Composable
private fun DateLine(label: String, date: FieldDate?, rowId: String?, rows: Map<String, FieldRow>, actions: CardActions, color: Color, locale: Locale) {
    if (date == null || rowId == null) return
    PaperLine(label, rows[rowId], actions) {
        Text(date.displayText(locale), color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** A labelled value on the document; a tap copies its row. */
@Composable
private fun PaperLine(label: String, row: FieldRow?, actions: CardActions, value: @Composable () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .then(if (row != null) Modifier.copyOnTap(row, actions) else Modifier)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Text(label, color = MaterialTheme.extraColors.paperLabel, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp, maxLines = 1)
        value()
    }
}

/** The fine wavy lines printed on documents, drawn behind the face. */
@Composable
private fun Guilloche(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = 1.dp.toPx())
        val lines = 9
        for (k in 0 until lines) {
            val y0 = size.height * (k + 1) / (lines + 1)
            val path = Path()
            var x = 0f
            path.moveTo(0f, y0)
            while (x <= size.width) {
                val y = y0 + sin(x / 26.dp.toPx() + k * 0.7f) * 7.dp.toPx() * 0.6f + sin(x / 61.dp.toPx() + k) * 5.dp.toPx() * 0.6f
                path.lineTo(x, y)
                x += 6.dp.toPx()
            }
            drawPath(path, color, style = stroke)
        }
    }
}

/**
 * An SSH login as a terminal (SPEC §6.2), dark in either theme: the command that connects, host / user / port, the
 * key from KeePassXC's agent settings with its fingerprint (a tap copies the public key), and a `~/.ssh/config` block.
 * "Copy ssh command"; "Open in …" when an app on the phone opens `ssh://` links.
 */
@Composable
internal fun SshSection(section: CardSection, login: SshLogin, key: SshKeyView?, sshApp: SshApp?, hasPassword: Boolean, reveal: RevealState, now: State<Instant>, actions: CardActions) {
    val location: SshKeyLocation? = key?.location ?: login.keyLocation
    val command = login.command(location)
    val commandCopied = stringResource(R.string.entry_ssh_copied_command)
    Titled(section.title) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FaceWidth {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .shadow(8.dp, RoundedCornerShape(22.dp))
                        .clip(RoundedCornerShape(22.dp))
                        .background(Terminal.background)
                        // Its edge, seen against the dark theme's background.
                        .border(1.dp, Terminal.rule, RoundedCornerShape(22.dp))
                        .padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { repeat(3) { Box(Modifier.size(10.dp).clip(CircleShape).background(Terminal.dot)) } }
                        Text(stringResource(R.string.entry_ssh_heading), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, letterSpacing = 1.4.sp, color = Terminal.label)
                        val port = login.port ?: 22
                        Text(
                            when {
                                location != null -> stringResource(R.string.entry_ssh_chip_key, port)
                                hasPassword -> stringResource(R.string.entry_ssh_chip_password, port)
                                else -> stringResource(R.string.entry_ssh_chip_port, port)
                            },
                            Modifier.clip(RoundedCornerShape(9.dp)).background(Terminal.chip).padding(horizontal = 10.dp, vertical = 4.dp),
                            color = Terminal.ink,
                            fontFamily = MonospaceFamily,
                            fontSize = 12.sp,
                            maxLines = 1,
                        )
                    }
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = Terminal.prompt)) { append("$ ") }
                            append(command)
                        },
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClickLabel = stringResource(R.string.entry_ssh_copy_command_label)) { actions.onCopyText(commandCopied, command) }
                            .padding(8.dp),
                        color = Terminal.ink,
                        fontFamily = MonospaceFamily,
                        fontSize = 16.sp,
                        lineHeight = 24.sp,
                    )
                    HorizontalDivider(color = Terminal.rule)
                    Column {
                        val host = stringResource(R.string.entry_ssh_copied_host)
                        TerminalLine(stringResource(R.string.entry_ssh_host), stringResource(R.string.entry_ssh_copy_host), login.host) { actions.onCopyText(host, login.host) }
                        login.user?.let { user ->
                            val copied = stringResource(R.string.entry_ssh_copied_user)
                            val note = if (login.userRowId == RowIds.USERNAME) stringResource(R.string.entry_ssh_the_username) else null
                            TerminalLine(stringResource(R.string.entry_ssh_user), stringResource(R.string.entry_ssh_copy_user), user, note = note) { actions.onCopyText(copied, user) }
                        }
                        val port = stringResource(R.string.entry_ssh_copied_port)
                        TerminalLine(stringResource(R.string.entry_ssh_port), stringResource(R.string.entry_ssh_copy_port), (login.port ?: 22).toString()) { actions.onCopyText(port, (login.port ?: 22).toString()) }
                    }
                    key?.let { KeyLine(it, actions) }
                    HorizontalDivider(color = Terminal.rule)
                    val config = login.config(location)
                    val block = stringResource(R.string.entry_ssh_copied_host_block)
                    Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("~/.ssh/config", Modifier.weight(1f), color = Terminal.label, fontFamily = MonospaceFamily, fontSize = 12.sp)
                        TextButton(onClick = { actions.onCopyText(block, config) }) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = null, Modifier.size(16.dp), tint = Terminal.ink)
                            Spacer(Modifier.size(6.dp))
                            Text(stringResource(R.string.entry_ssh_copy), color = Terminal.ink)
                        }
                    }
                    Text(
                        config,
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Terminal.code).padding(horizontal = 10.dp, vertical = 8.dp),
                        color = Terminal.ink,
                        fontFamily = MonospaceFamily,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                    )
                    Row(Modifier.heightIn(min = 40.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.entry_face_tap_line), style = MaterialTheme.typography.bodySmall, color = Terminal.label)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { actions.onCopyText(commandCopied, command) }, Modifier.heightIn(min = 52.dp)) {
                        Icon(Icons.Rounded.Terminal, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.entry_ssh_copy_command), maxLines = 1)
                    }
                    sshApp?.let { app ->
                        FilledTonalButton(onClick = app.open, Modifier.heightIn(min = 52.dp)) {
                            Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                            Text(stringResource(R.string.entry_ssh_open_in, app.label), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            val others = section.rows.filter { it.id !in login.faceRowIds }
            if (others.isNotEmpty()) RowGroup(others) { row, shape -> FieldRowView(row, reveal, now, actions, shape) }
        }
    }
}

/** An app on the phone that opens `ssh://` links (ConnectBot, JuiceSSH, Termius). */
internal class SshApp(val label: String, val open: () -> Unit)

@Composable
private fun TerminalLine(key: String, copyLabel: String, value: String, note: String? = null, onCopy: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClickLabel = copyLabel, onClick = onCopy)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(key, Modifier.width(44.dp), color = Terminal.label, fontFamily = MonospaceFamily, fontSize = 15.sp)
        Text(value, Modifier.weight(1f), color = Terminal.ink, fontFamily = MonospaceFamily, fontSize = 15.sp)
        note?.let { Text(it, color = Terminal.label, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
    }
}

@Composable
private fun KeyLine(key: SshKeyView, actions: CardActions) {
    val publicKey = key.publicKey
    val copied = stringResource(R.string.entry_ssh_copied_public_key)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .then(if (publicKey != null) Modifier.clickable(onClickLabel = stringResource(R.string.entry_ssh_copy_public_key)) { actions.onCopyText(copied, publicKey.line) } else Modifier)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.Key, contentDescription = null, Modifier.padding(top = 2.dp).size(18.dp), tint = Terminal.prompt)
        Column(Modifier.weight(1f)) {
            val name = key.location.name.substringAfterLast('/')
            Text(listOfNotNull(name, publicKey?.displayType).joinToString(" · "), color = Terminal.ink, fontFamily = MonospaceFamily, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            publicKey?.let { Text(it.fingerprint, color = Terminal.label, fontFamily = MonospaceFamily, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Text(
                key.problem?.resolve() ?: when (key.location) {
                    is SshKeyLocation.InAttachment -> stringResource(R.string.entry_ssh_key_in_attachment)
                    is SshKeyLocation.InFile -> stringResource(R.string.entry_ssh_key_in_file)
                },
                color = Terminal.label,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** The terminal's colours: the same in light and dark. */
private object Terminal {
    val background = Color(0xFF15171C)
    val ink = Color(0xFFE6E8EE)
    val label = Color(0xFF9AA0AD)
    val prompt = Color(0xFF7EE2A8)
    val dot = Color(0xFF3A3E48)
    val chip = Color(0xFF262A33)
    val rule = Color(0xFF2C3039)
    val code = Color(0xFF0F1115)
}

/** The square tile in front of a face's heading. */
@Composable
private fun FaceTile(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

/** The country as a chip: its flag and name; Antarctica's flag and "Country unknown" when nothing says it for sure. */
@Composable
private fun CountryChip(code: String?) {
    Row(
        Modifier.heightIn(min = 28.dp).clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(code?.let(Countries::flag) ?: Countries.UNKNOWN_FLAG, fontSize = 14.sp)
        Text(code?.let(Countries::name) ?: stringResource(R.string.entry_country_unknown), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 1)
    }
}

/** A small labelled value of a face, as wide as its text; a tap copies its row. */
@Composable
private fun FaceItem(label: String, row: FieldRow?, actions: CardActions, value: @Composable () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .then(if (row != null) Modifier.copyOnTap(row, actions) else Modifier)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.2.sp, color = MaterialTheme.extraColors.stickerLabel, maxLines = 1)
        value()
    }
}

@Composable
private fun ShowButton(shown: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(if (shown) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        Text(stringResource(if (shown) R.string.entry_face_hide else R.string.entry_face_show))
    }
}
