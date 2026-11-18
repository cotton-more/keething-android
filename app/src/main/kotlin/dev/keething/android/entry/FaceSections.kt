package dev.keething.android.entry

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.automirrored.rounded._360
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import dev.keething.android.R
import dev.keething.android.theme.MonospaceFamily
import dev.keething.android.theme.extraColors
import dev.keething.android.ui.BrandMark
import dev.keething.android.ui.CardChip
import dev.keething.android.ui.ContactlessMark
import dev.keething.android.ui.QrCodeImage
import dev.keething.android.ui.cardArt
import dev.keething.android.ui.colouredSecret
import dev.keething.android.ui.spokenMasked
import dev.keething.card.CardSection
import dev.keething.card.EntryAvatar
import dev.keething.card.ExpiryHint
import dev.keething.card.FieldRow
import dev.keething.card.PaymentCard
import dev.keething.card.RevealState
import dev.keething.card.WiFiNetwork
import java.time.Instant
import java.util.Locale
import kotlin.math.abs

/** A face is at most this wide: a payment card stays card-sized on a wide screen. */
internal val FACE_MAX_WIDTH = 440.dp

/** A face's drawn part and its buttons: at most [FACE_MAX_WIDTH], centred; the rows under it take the card's width. */
@Composable
internal fun FaceWidth(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = FACE_MAX_WIDTH).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

/**
 * A payment card as a card (SPEC §6.2): the number, holder and expiry on its front copy with a tap, the CVC on its
 * back; "Turn over" or a sideways swipe flips it. Under it only what the card does not show (the PIN, other fields).
 */
@Composable
internal fun PaymentCardSection(
    section: CardSection,
    card: PaymentCard,
    avatar: EntryAvatar,
    reveal: RevealState,
    now: androidx.compose.runtime.State<Instant>,
    actions: CardActions,
) {
    var back by rememberSaveable(section.id) { mutableStateOf(false) }
    val rows = remember(section) { section.rows.associateBy { it.id } }
    val secrets = listOfNotNull(rows[card.numberRowId], card.cvcRowId?.let(rows::get)).filter { it.isConcealed && !it.isEmpty }
    val shown = secrets.isNotEmpty() && secrets.all { reveal.isRevealed(it.id) }
    val canFlip = card.cvcRowId != null
    Titled(section.title) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FaceWidth {
                CardFace(card, rows, cardArt(card.network, avatar), reveal, actions, back = back && canFlip, onFlip = if (canFlip) ({ back = !back }) else null)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    if (secrets.isNotEmpty()) {
                        FilledTonalButton(onClick = { actions.onToggleRows(secrets) }, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                            Icon(if (shown) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                            Text(stringResource(if (shown) R.string.entry_face_hide else R.string.entry_face_show))
                        }
                    }
                    if (canFlip) {
                        FilledTonalButton(onClick = { back = !back }, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                            Icon(Icons.AutoMirrored.Rounded._360, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                            // The same both ways: a label that changed would move what follows it while the card turns.
                            Text(stringResource(R.string.entry_card_face_turn_over))
                        }
                    }
                    card.expiryHint?.takeIf { it.status != ExpiryHint.Status.VALID }?.let { ExpiryChip(it) }
                }
                if (card.checkDigitOk == false) {
                    Note(Icons.Rounded.ErrorOutline, stringResource(R.string.entry_card_face_check_digit), MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            val others = section.rows.filter { it.id !in card.faceRowIds }
            if (others.isNotEmpty()) RowGroup(others) { row, shape -> FieldRowView(row, reveal, now, actions, shape) }
        }
    }
}

@Composable
private fun CardFace(card: PaymentCard, rows: Map<String, FieldRow>, art: List<Color>, reveal: RevealState, actions: CardActions, back: Boolean, onFlip: (() -> Unit)?) {
    val rotation by animateFloatAsState(
        if (back) 180f else 0f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessLow),
        label = "flip",
    )
    val density = LocalDensity.current
    val threshold = with(density) { 56.dp.toPx() }
    var dragged by remember { mutableFloatStateOf(0f) }
    val shape = RoundedCornerShape(22.dp)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .widthIn(max = FACE_MAX_WIDTH)
                .fillMaxWidth()
                .aspectRatio(1.586f)
                .graphicsLayer {
                    rotationY = rotation
                    cameraDistance = 14f * density.density
                }
                .then(
                    if (onFlip == null) {
                        Modifier
                    } else {
                        Modifier.draggable(
                            rememberDraggableState { dragged += it },
                            Orientation.Horizontal,
                            onDragStopped = {
                                if (abs(dragged) > threshold) onFlip()
                                dragged = 0f
                            },
                        )
                    },
                ),
        ) {
            val surface = Modifier.fillMaxSize().shadow(10.dp, shape).clip(shape).background(Brush.linearGradient(art))
            if (rotation <= 90f) {
                CardFront(card, rows, reveal, actions, surface)
            } else {
                CardBack(card, rows, reveal, actions, surface.graphicsLayer { rotationY = 180f })
            }
        }
    }
}

@Composable
private fun CardFront(card: PaymentCard, rows: Map<String, FieldRow>, reveal: RevealState, actions: CardActions, modifier: Modifier) {
    val number = rows[card.numberRowId]
    val revealed = reveal.isRevealed(card.numberRowId)
    Column(modifier.padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            CardChip(40.dp)
            Spacer(Modifier.size(10.dp))
            ContactlessMark(Modifier.size(24.dp))
            Spacer(Modifier.weight(1f))
            BrandMark(card.network, card.brand, 28.dp)
        }
        val spokenNumber = stringResource(R.string.entry_card_face_number, RevealState.spoken(card.maskedNumber).lowercase())
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .then(if (number != null) Modifier.copyOnTap(number, actions) else Modifier)
                .padding(horizontal = 6.dp, vertical = 4.dp)
                .semantics { if (!revealed) contentDescription = spokenNumber },
        ) {
            BasicText(
                if (revealed) card.groupedNumber ?: number?.revealedText().orEmpty() else card.maskedNumber,
                style = TextStyle(color = Color.White, fontFamily = MonospaceFamily, fontSize = 23.sp, letterSpacing = 1.5.sp),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = 23.sp),
            )
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val holder = card.holderRowId?.let(rows::get)
            FaceLine(stringResource(R.string.entry_card_face_holder), Modifier.weight(1f), holder, actions, Alignment.Start) {
                Text(
                    card.holder.orEmpty().uppercase(Locale.getDefault()),
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            card.expiry?.let { expiry ->
                val expired = card.expiryHint?.status == ExpiryHint.Status.EXPIRED
                FaceLine(stringResource(R.string.entry_card_face_valid_thru), Modifier, card.expiryRowId?.let(rows::get), actions, Alignment.End) {
                    Text(
                        "%02d/%02d".format(Locale.ROOT, expiry.date.month, expiry.date.year % 100),
                        color = if (expired) Color(0xFFFFB4AB) else Color.White,
                        fontFamily = MonospaceFamily,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/** A labelled value on the card's face; a tap copies its row. */
@Composable
private fun FaceLine(label: String, modifier: Modifier, row: FieldRow?, actions: CardActions, alignment: Alignment.Horizontal, value: @Composable () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .then(if (row != null) Modifier.copyOnTap(row, actions) else Modifier)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalAlignment = alignment,
    ) {
        Text(label, color = Color.White.copy(alpha = 0.75f), fontSize = 9.sp, letterSpacing = 1.2.sp, maxLines = 1)
        value()
    }
}

@Composable
private fun CardBack(card: PaymentCard, rows: Map<String, FieldRow>, reveal: RevealState, actions: CardActions, modifier: Modifier) {
    val cvc = card.cvcRowId?.let(rows::get)
    Column(modifier) {
        Spacer(Modifier.height(22.dp))
        Box(Modifier.fillMaxWidth().height(44.dp).background(Color(0xE60A0A0E)))
        Row(Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFF0EEE7))
                    .drawBehind {
                        val step = 6.dp.toPx()
                        var x = -size.height
                        while (x < size.width) {
                            drawLine(Color(0xFFE0DDD2), Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = step / 2)
                            x += step
                        }
                    },
            )
            if (cvc != null) {
                Column(
                    Modifier.clip(RoundedCornerShape(8.dp)).copyOnTap(cvc, actions).padding(2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.entry_card_face_cvc), color = Color.White.copy(alpha = 0.8f), fontSize = 9.sp, letterSpacing = 1.2.sp)
                    Box(
                        Modifier.heightIn(min = 40.dp).widthIn(min = 72.dp).clip(RoundedCornerShape(6.dp)).background(Color.White).padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        val revealed = !cvc.isConcealed || reveal.isRevealed(cvc.id)
                        Text(
                            if (revealed) cvc.revealedText() else "•••",
                            if (revealed) Modifier else Modifier.spokenMasked("•••"),
                            color = Color(0xFF15151A),
                            fontFamily = MonospaceFamily,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 2.sp,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.entry_card_face_tap_cvc), Modifier.weight(1f), color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(card.brand.orEmpty(), color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/** When the card ends, if soon or past: the one place the card says it (no banner, no row). */
@Composable
internal fun ExpiryChip(hint: ExpiryHint) {
    val expired = hint.status == ExpiryHint.Status.EXPIRED
    val (container, content) = if (expired) {
        MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.extraColors.warnContainer to MaterialTheme.extraColors.onWarnContainer
    }
    Row(
        Modifier.heightIn(min = 32.dp).clip(RoundedCornerShape(10.dp)).background(container).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Rounded.Schedule, contentDescription = null, Modifier.size(16.dp), tint = content)
        Text(hint.text, style = MaterialTheme.typography.labelLarge, color = content)
    }
}

@Composable
internal fun Note(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, container: Color, content: Color) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(container).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, Modifier.size(18.dp), tint = content)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = content)
    }
}

/**
 * A Wi-Fi network as the label under a router (SPEC §6.2): its name and key copy with a tap, the QR code beside them
 * is blurred until the key is shown (it holds the key) and opens full screen. "Add to this phone" hands the network to
 * the system's own "Save network?". Under it only the fields the label does not show.
 */
@Composable
internal fun WiFiSection(
    section: CardSection,
    network: WiFiNetwork,
    payload: String?,
    reveal: RevealState,
    now: androidx.compose.runtime.State<Instant>,
    actions: CardActions,
) {
    val colors = MaterialTheme.extraColors
    val rows = remember(section) { section.rows.associateBy { it.id } }
    val ssid = network.ssidRowId?.let(rows::get)
    val key = network.passwordRowId?.let(rows::get)
    val keyShown = key != null && reveal.isRevealed(key.id)
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
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.Wifi, contentDescription = null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Text(stringResource(R.string.entry_wifi_heading), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, letterSpacing = 1.4.sp, color = colors.stickerLabel, maxLines = 1)
                        SecurityChip(network)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            StickerLine(stringResource(R.string.entry_wifi_name), ssid, actions) {
                                Text(network.ssid, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            }
                            if (key != null) {
                                StickerLine(stringResource(R.string.entry_wifi_key), key, actions) {
                                    if (keyShown) {
                                        Text(colouredSecret(key.revealedText()), style = MaterialTheme.typography.bodyLarge, fontFamily = MonospaceFamily)
                                    } else {
                                        Text(RevealState.MASK, Modifier.spokenMasked(RevealState.MASK), style = MaterialTheme.typography.bodyLarge, letterSpacing = 2.sp, maxLines = 1)
                                    }
                                }
                            }
                        }
                        val qr = Modifier.size(112.dp).clip(RoundedCornerShape(14.dp)).background(Color.White)
                        if (payload != null) {
                            Box(qr.clickable(onClickLabel = stringResource(R.string.entry_wifi_qr_full_screen), onClick = actions.onWiFiFullScreen).padding(6.dp)) {
                                QrCodeImage(payload, description = stringResource(R.string.entry_wifi_qr_join, network.ssid), modifier = Modifier.fillMaxSize())
                            }
                        } else if (key != null) {
                            // The code holds the key: blurred, and an empty one under the blur, until the key is shown.
                            Box(qr.clickable(onClickLabel = stringResource(R.string.entry_wifi_show_key_and_qr)) { actions.onToggle(key.id) }, contentAlignment = Alignment.Center) {
                                QrCodeImage(network.qrPayload(""), description = "", modifier = Modifier.fillMaxSize().padding(6.dp).blur(6.dp))
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Rounded.Lock, contentDescription = null, Modifier.size(22.dp), tint = Color(0xFF1B1B22))
                                    Text(stringResource(R.string.entry_face_show), color = Color(0xFF1B1B22), style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = colors.stickerEdge)
                    Row(Modifier.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when {
                                key == null -> stringResource(R.string.entry_face_tap_line)
                                keyShown -> stringResource(R.string.entry_wifi_tap_code)
                                else -> stringResource(R.string.entry_wifi_code_holds_key)
                            },
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.stickerLabel,
                        )
                        if (key != null) {
                            TextButton(onClick = { actions.onToggle(key.id) }) {
                                Icon(if (keyShown) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                                Text(stringResource(if (keyShown) R.string.entry_face_hide else R.string.entry_face_show))
                            }
                        }
                    }
                }
                // The code also opens from the label itself: here a square button, so that "Add to this phone" keeps the row.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    actions.onAddNetwork?.let { add ->
                        Button(onClick = add, Modifier.weight(1f).heightIn(min = 52.dp)) {
                            Icon(Icons.Rounded.PhoneAndroid, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                            Text(stringResource(R.string.entry_wifi_add), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    FilledTonalIconButton(onClick = actions.onWiFiFullScreen, Modifier.size(52.dp)) {
                        Icon(Icons.Rounded.QrCode2, contentDescription = stringResource(R.string.entry_qr_full_screen))
                    }
                }
            }
            val others = section.rows.filter { it.id !in network.faceRowIds }
            if (others.isNotEmpty()) RowGroup(others) { row, shape -> FieldRowView(row, reveal, now, actions, shape) }
        }
    }
}

/** A labelled line of the label; a tap copies its row. */
@Composable
internal fun StickerLine(label: String, row: FieldRow?, actions: CardActions, value: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(if (row != null) Modifier.copyOnTap(row, actions) else Modifier)
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.2.sp, color = MaterialTheme.extraColors.stickerLabel, maxLines = 1)
        value()
    }
}

@Composable
private fun SecurityChip(network: WiFiNetwork) {
    val open = network.isOpen
    val container = if (open) MaterialTheme.extraColors.warnContainer else MaterialTheme.colorScheme.secondaryContainer
    val content = if (open) MaterialTheme.extraColors.onWarnContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Row(
        Modifier.heightIn(min = 28.dp).clip(RoundedCornerShape(9.dp)).background(container).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(if (open) Icons.Rounded.LockOpen else Icons.Rounded.Lock, contentDescription = null, Modifier.size(14.dp), tint = content)
        Text(if (open) stringResource(R.string.entry_wifi_open) else network.securityLabel, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
    }
}
