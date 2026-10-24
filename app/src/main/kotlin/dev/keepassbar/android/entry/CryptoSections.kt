package dev.keepassbar.android.entry

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.keepassbar.android.theme.MonospaceFamily
import dev.keepassbar.android.theme.extraColors
import dev.keepassbar.android.ui.NetworkBadge
import dev.keepassbar.android.ui.QrCodeImage
import dev.keepassbar.android.ui.SegmentGap
import dev.keepassbar.android.ui.segmentShape
import dev.keepassbar.card.AddressCheck
import dev.keepassbar.card.CardSection
import dev.keepassbar.card.CryptoNetwork
import dev.keepassbar.card.CryptoWallet
import dev.keepassbar.card.FieldRow
import dev.keepassbar.card.RevealState
import dev.keepassbar.card.SeedPhrase
import java.time.Instant

/**
 * A crypto wallet (SPEC §6.2): the recovery phrase on a steel plate, as such phrases are kept, hidden until shown, then a
 * numbered grid with its check; its passphrase on the plate; neither is ever copied (no copy button, no QR code, no
 * share), only shown or read a word at a time ([onWords]). The first receiving address with its network, its check and
 * its QR code (full screen with [onAddressQr]); the other addresses, the extended public key and the path as rows.
 */
@Composable
internal fun CryptoSection(section: CardSection, wallet: CryptoWallet, reveal: RevealState, now: State<Instant>, actions: CardActions) {
    val rows = remember(section) { section.rows.associateBy { it.id } }
    Titled(section.title) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FaceWidth {
                wallet.seedRowId?.let { id -> rows[id]?.let { SeedPlate(it, wallet.passphraseRowId?.let(rows::get), reveal, actions) } }
                wallet.privateKeyRowId?.let { id ->
                    Note(
                        Icons.Rounded.WarningAmber,
                        "“${rows[id]?.label.orEmpty()}” holds a private key: whoever has it has the wallet. It is shown, never copied.",
                        MaterialTheme.extraColors.warnContainer,
                        MaterialTheme.extraColors.onWarnContainer,
                    )
                }
                wallet.addresses.firstOrNull()?.let { first -> ReceiveCard(first, rows[first.rowId], actions) }
            }
            val details = buildList<@Composable (Shape) -> Unit> {
                for (address in wallet.addresses.drop(1)) {
                    add { shape -> AddressRow(address, rows[address.rowId], shape, actions) }
                }
                wallet.xpub?.let { key ->
                    add { shape ->
                        DetailRow(
                            label = key.label, value = key.text, row = rows[key.rowId], shape = shape, actions = actions,
                            note = if (key.read?.passes == false) "Fails its checksum. A typo?" else "Shows every address and balance of the wallet: share it with care",
                            noteColor = if (key.read?.passes == false) MaterialTheme.colorScheme.error else MaterialTheme.extraColors.warn,
                            ellipsize = true,
                        )
                    }
                }
                wallet.path?.let { path ->
                    add { shape -> DetailRow(label = rows[path.rowId]?.label.orEmpty(), value = path.text, row = rows[path.rowId], shape = shape, actions = actions, note = path.description) }
                }
                for (row in section.rows.filter { it.id !in wallet.faceRowIds && it.id != wallet.privateKeyRowId }) {
                    add { shape -> FieldRowView(row, reveal, now, actions, shape) }
                }
                wallet.privateKeyRowId?.let { id -> rows[id]?.let { row -> add { shape -> FieldRowView(row, reveal, now, actions, shape) } } }
            }
            if (details.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(SegmentGap)) {
                    details.forEachIndexed { i, item -> item(segmentShape(i, details.size, outer = 22.dp)) }
                }
            }
        }
    }
}

/** The plate's colours: steel, the same in light and dark. */
private object Steel {
    val top = Color(0xFFE6E9ED)
    val middle = Color(0xFFCDD2D9)
    val bottom = Color(0xFFC3C8D0)
    val ink = Color(0xFF20252D)
    val label = Color(0xFF4F5560)
    val number = Color(0xFF5E6570)
    val cell = Color(0x122A2F37)
    val rule = Color(0x292A2F37)
    val button = Color(0xFF2A2F37)
    val onButton = Color(0xFFF2F4F7)
    val ok = Color(0xFF1B5E33)
    val okBack = Color(0x241F6B3A)
    val bad = Color(0xFF8C1D18)
    val badBack = Color(0x21B3261E)
    val badEdge = Color(0xFFB3261E)
}

/** Brushed steel: a soft gradient and fine vertical lines. */
private fun Modifier.brushedSteel(shape: Shape): Modifier = this
    .clip(shape)
    .background(Brush.verticalGradient(listOf(Steel.top, Steel.middle, Steel.bottom)))
    .drawBehind {
        val step = 3.dp.toPx()
        var x = 0f
        while (x < size.width) {
            drawLine(Color.White.copy(alpha = 0.16f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            x += step
        }
    }

@Composable
private fun SeedPlate(seed: FieldRow, passphrase: FieldRow?, reveal: RevealState, actions: CardActions) {
    val shown = reveal.isRevealed(seed.id)
    // Read only while shown: the phrase is a secret like the password, and its words are the wallet.
    val phrase = remember(seed, shown) { if (shown) SeedPhrase.read(seed.storedText()) else null }
    val shape = RoundedCornerShape(22.dp)
    val edge = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0x24FFFFFF) else Color(0x1A000000)
    Column(
        Modifier
            .fillMaxWidth()
            .shadow(6.dp, shape)
            .brushedSteel(shape)
            .border(1.dp, edge, shape)
            .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Rivet()
            Text("RECOVERY PHRASE", Modifier.weight(1f), color = Steel.label, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.6.sp, maxLines = 1)
            phrase?.let {
                val language = (it.check as? SeedPhrase.Check.Bip39)?.language?.displayName
                Text(
                    listOfNotNull("${it.words.size} words", language).joinToString(" · "),
                    Modifier.clip(RoundedCornerShape(9.dp)).background(Steel.cell).padding(horizontal = 10.dp, vertical = 4.dp),
                    color = Steel.ink,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
            Rivet()
        }
        if (phrase == null) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(Steel.cell), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Lock, contentDescription = null, tint = Steel.label)
                }
                Text(
                    if (seed.isEmpty) "Empty" else "Whoever has these words has the wallet. They are shown here, never copied.",
                    Modifier.widthIn(max = 300.dp),
                    color = Steel.label,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                if (!seed.isEmpty) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { actions.onToggle(seed.id) },
                            colors = ButtonDefaults.buttonColors(containerColor = Steel.button, contentColor = Steel.onButton),
                            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                        ) {
                            Icon(Icons.Rounded.Visibility, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                            Text("Show")
                        }
                        Button(
                            onClick = actions.onSeedWords,
                            colors = ButtonDefaults.buttonColors(containerColor = Steel.cell, contentColor = Steel.ink),
                            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                        ) {
                            Icon(Icons.Rounded.TextFields, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                            Text("Word by word")
                        }
                    }
                }
            }
        } else {
            WordGrid(phrase)
            PhraseCheck(phrase)
        }
        passphrase?.let { PassphraseLine(it, reveal, actions) }
        HorizontalDivider(color = Steel.rule)
        // The note on a line of its own, the buttons under it at the end: beside them it would be squeezed.
        Column(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(top = 10.dp, bottom = if (phrase == null) 10.dp else 0.dp)) {
            Text("Never copied · not in the clipboard, not in a QR code", color = Steel.label, style = MaterialTheme.typography.bodySmall)
            if (phrase != null) {
                Row(Modifier.align(Alignment.End)) {
                    TextButton(onClick = actions.onSeedWords) { Text("Word by word", color = Steel.ink) }
                    TextButton(onClick = { actions.onToggle(seed.id) }) { Text("Hide", color = Steel.ink) }
                }
            }
        }
    }
}

@Composable
private fun Rivet() {
    Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF9AA1AB)))
}

/** The words, numbered, three to a line (two with large text); a word that is in no list outlined in red. */
@Composable
private fun WordGrid(phrase: SeedPhrase) {
    val bad = (phrase.check as? SeedPhrase.Check.UnknownWord)?.index
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val scale = LocalDensity.current.fontScale
        val columns = if (maxWidth / scale < 330.dp) 2 else 3
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.semantics { contentDescription = "The ${phrase.words.size} words" }) {
            for (line in phrase.words.withIndex().chunked(columns)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((i, word) in line) {
                        Row(
                            Modifier
                                .weight(1f)
                                .heightIn(min = 36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Steel.cell)
                                .then(if (i == bad) Modifier.border(2.dp, Steel.badEdge, RoundedCornerShape(10.dp)) else Modifier)
                                .padding(horizontal = 8.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("${i + 1}", Modifier.width(18.dp), color = Steel.number, fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, maxLines = 1)
                            Text(word, color = if (i == bad) Steel.bad else Steel.ink, fontFamily = MonospaceFamily, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    repeat(columns - line.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun PhraseCheck(phrase: SeedPhrase) {
    val ok = phrase.passes
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (ok) Steel.okBack else Steel.badBack).padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(if (ok) Icons.Rounded.Check else Icons.Rounded.ErrorOutline, contentDescription = null, Modifier.size(18.dp), tint = if (ok) Steel.ok else Steel.bad)
        Text(phrase.summary(), color = if (ok) Steel.ok else Steel.bad, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

/** The passphrase on the plate: masked, with its own eye; never copied. */
@Composable
private fun PassphraseLine(row: FieldRow, reveal: RevealState, actions: CardActions) {
    val shown = reveal.isRevealed(row.id)
    HorizontalDivider(color = Steel.rule)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text("+ PASSPHRASE", color = Steel.label, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.2.sp)
            Text(
                reveal.displayValue(row),
                color = Steel.ink,
                fontFamily = MonospaceFamily,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = if (shown) 0.sp else 2.sp,
            )
            Text("The 25th word: without it the phrase opens another wallet", color = Steel.label, style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onClick = { actions.onToggle(row.id) }) {
            Icon(
                if (shown) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                contentDescription = if (shown) "Hide the passphrase" else "Show the passphrase",
                tint = Steel.label,
            )
        }
    }
}

/** An address in groups of four, the first two and last two bold: what to check where it is pasted. */
internal fun groupedAddress(text: String, strong: Color, weak: Color) = buildAnnotatedString {
    val groups = if (text.startsWith("0x")) listOf("0x") + text.drop(2).chunked(4) else text.chunked(4)
    groups.forEachIndexed { i, group ->
        if (i > 0) append(' ')
        val edge = i < 2 || i >= groups.size - 2
        withStyle(SpanStyle(color = if (edge) strong else weak, fontWeight = if (edge) FontWeight.Bold else FontWeight.Normal)) { append(group) }
    }
}

/** The check of an address, as a chip: passes, has no checksum, or fails. */
@Composable
private fun CheckChip(address: CryptoWallet.Address) {
    val read = address.read ?: return
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val (text, back, ink) = when (read.check) {
        AddressCheck.PASSES -> Triple(
            if (read.network == CryptoNetwork.ETHEREUM) "EIP-55" else "checked",
            if (dark) Color(0xFF1E3B27) else Color(0xFFDDF3E2),
            if (dark) Color(0xFFB5E8C2) else Color(0xFF1B5E33),
        )
        AddressCheck.NONE -> Triple("no checksum", MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurfaceVariant)
        AddressCheck.FAILS -> Triple("fails its check", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
    }
    Row(
        Modifier.clip(RoundedCornerShape(9.dp)).background(back).padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (read.check == AddressCheck.PASSES) Icon(Icons.Rounded.Check, contentDescription = null, Modifier.size(14.dp), tint = ink)
        Text(text, color = ink, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

/** The first address as a label like the bank account's: its network, its QR code, the address grouped; Copy and Show QR. */
@Composable
private fun ReceiveCard(address: CryptoWallet.Address, row: FieldRow?, actions: CardActions) {
    val colors = MaterialTheme.extraColors
    val read = address.read
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(colors.sticker)
                .border(1.dp, colors.stickerEdge, RoundedCornerShape(22.dp))
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NetworkBadge(read?.network, 32.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        listOfNotNull("RECEIVE", read?.network?.displayName?.uppercase()).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        letterSpacing = 1.4.sp,
                        color = colors.stickerLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(read?.kind ?: address.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                CheckChip(address)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                QrCodeImage(
                    address.text,
                    description = "QR code of the address; full screen",
                    modifier = Modifier
                        .size(128.dp)
                        .border(1.dp, colors.stickerEdge, RoundedCornerShape(12))
                        .clickable(onClickLabel = "QR code full screen") { actions.onAddressQr(address.rowId) },
                )
                Text(
                    groupedAddress(address.text, MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.onSurfaceVariant),
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .then(if (row != null) Modifier.copyOnTap(row, actions) else Modifier)
                        .padding(6.dp),
                    fontFamily = MonospaceFamily,
                    fontSize = 15.sp,
                    lineHeight = 23.sp,
                )
            }
            HorizontalDivider(color = colors.stickerEdge)
            Row(Modifier.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Tap the address to copy it · start and end in bold, to check where it lands", style = MaterialTheme.typography.bodySmall, color = colors.stickerLabel)
            }
        }
        if (read?.check == AddressCheck.FAILS) {
            Note(Icons.Rounded.ErrorOutline, "The address fails its checksum (a typo?): check it before anyone sends to it", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (row != null) {
                Button(onClick = { actions.onCopy(row) }, Modifier.weight(1f).heightIn(min = 52.dp)) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text("Copy address", maxLines = 1)
                }
            }
            FilledTonalButton(onClick = { actions.onAddressQr(address.rowId) }, Modifier.weight(1f).heightIn(min = 52.dp)) {
                Icon(Icons.Rounded.QrCode2, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text("Show QR", maxLines = 1)
            }
        }
    }
}

/** Another address: its label, its start and end, a QR code full screen, Copy. */
@Composable
private fun AddressRow(address: CryptoWallet.Address, row: FieldRow?, shape: Shape, actions: CardActions) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(shape)
            .background(MaterialTheme.extraColors.row)
            .then(if (row != null) Modifier.copyOnTap(row, actions) else Modifier)
            .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NetworkBadge(address.read?.network, 28.dp)
        Column(Modifier.weight(1f)) {
            Text(address.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                groupedAddress(address.text, MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.onSurfaceVariant),
                fontFamily = MonospaceFamily,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (address.read?.check == AddressCheck.FAILS) {
                Text("Fails its checksum. A typo?", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        IconButton(onClick = { actions.onAddressQr(address.rowId) }) {
            Icon(Icons.Rounded.QrCode2, contentDescription = "QR code of ${address.label}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (row != null) {
            IconButton(onClick = { actions.onCopy(row) }) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy ${address.label}", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** The extended public key or the path: monospaced, a note under it, Copy. */
@Composable
private fun DetailRow(
    label: String, value: String, row: FieldRow?, shape: Shape, actions: CardActions, note: String?,
    noteColor: Color = MaterialTheme.colorScheme.onSurfaceVariant, ellipsize: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(shape)
            .background(MaterialTheme.extraColors.row)
            .then(if (row != null) Modifier.copyOnTap(row, actions) else Modifier)
            .padding(start = 18.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (ellipsize && value.length > 26) value.take(14) + "…" + value.takeLast(8) else value,
                fontFamily = MonospaceFamily,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
            )
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = noteColor) }
        }
        if (row != null && row.isCopyable) {
            IconButton(onClick = { actions.onCopy(row) }) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy ${row.noticeLabel.lowercase()}", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
