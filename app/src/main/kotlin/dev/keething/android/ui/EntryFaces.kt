package dev.keething.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.keething.android.theme.MonospaceFamily
import dev.keething.android.theme.extraColors
import dev.keething.card.Countries
import dev.keething.card.CryptoNetwork
import dev.keething.card.CardBrand
import dev.keething.card.EntryAvatar
import dev.keething.card.EntryFace

/**
 * Card art per network, light to dark (Mac `PaymentCardView.gradient`); a network without art of its own takes the
 * entry's colour, so that two such cards still look different.
 */
internal fun cardArt(network: CardBrand?, avatar: EntryAvatar): List<Color> = when (network) {
    CardBrand.VISA -> listOf(Color(0xFF2140A0), Color(0xFF0D1F61))
    CardBrand.MASTERCARD -> listOf(Color(0xFF404047), Color(0xFF1A1A1F))
    CardBrand.AMEX -> listOf(Color(0xFF298599), Color(0xFF0F546B))
    CardBrand.MIR -> listOf(Color(0xFF2E8C59), Color(0xFF125938))
    else -> AVATAR_PALETTE[avatar.colorIndex.coerceIn(AVATAR_PALETTE.indices)].toList()
}

/** The network's mark on a card, drawn simply (a wordmark, Mastercard's circles); [unit] is its height. */
@Composable
internal fun BrandMark(network: CardBrand?, brand: String?, unit: Dp, modifier: Modifier = Modifier) {
    val size = unit.value
    when (network) {
        CardBrand.VISA -> Text("VISA", modifier, color = Color.White, fontSize = (size * 0.95f).sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, maxLines = 1)
        CardBrand.MASTERCARD -> Row(modifier) {
            Box(Modifier.size(unit).clip(CircleShape).background(Color(0xFFEB001B)))
            Box(Modifier.offset(x = -unit * 0.4f).size(unit).clip(CircleShape).background(Color(0xEBF79E1B)))
        }
        CardBrand.AMEX -> Text(
            "AMERICAN\nEXPRESS",
            modifier.border(1.dp, Color.White, RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 2.dp),
            color = Color.White,
            fontSize = (size * 0.32f).sp,
            lineHeight = (size * 0.36f).sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
        )
        CardBrand.MIR -> Text("МИР", modifier, color = Color.White, fontSize = (size * 0.8f).sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        else -> brand?.let { Text(it, modifier, color = Color.White, fontSize = (size * 0.55f).sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
    }
}

/** A card's chip, gold with its contact lines; [width] wide. */
@Composable
internal fun CardChip(width: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(width, width * 0.75f)) {
        val corner = CornerRadius(size.width * 0.15f)
        drawRoundRect(Color(0xFFE3C26F), cornerRadius = corner)
        val line = Color(0x7350370A)
        val stroke = size.width * 0.035f
        val third = size.width / 3
        for (y in listOf(size.height / 3, size.height * 2 / 3)) {
            drawLine(line, Offset(0f, y), Offset(third, y), stroke)
            drawLine(line, Offset(size.width - third, y), Offset(size.width, y), stroke)
        }
        drawLine(line, Offset(third, 0f), Offset(third, size.height), stroke)
        drawLine(line, Offset(size.width - third, 0f), Offset(size.width - third, size.height), stroke)
        drawLine(line, Offset(third, size.height / 2), Offset(size.width - third, size.height / 2), stroke)
    }
}

/** The contactless mark: four arcs opening to the right, white. */
@Composable
internal fun ContactlessMark(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = size.width * 0.09f, cap = StrokeCap.Round)
        for (i in 0..3) {
            val radius = size.width * (0.14f + i * 0.13f)
            val center = Offset(size.width * 0.12f, size.height / 2)
            drawArc(
                Color.White.copy(alpha = 0.85f), startAngle = -50f, sweepAngle = 100f, useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius), size = Size(radius * 2, radius * 2), style = stroke,
            )
        }
    }
}

/**
 * An entry's picture in the list and on its card's header: the small face of a payment card, a Wi-Fi network, a bank
 * account, an identity document or an SSH login (SPEC §6.2), else its icon or monogram.
 */
@Composable
internal fun EntryMark(avatar: EntryAvatar, image: ImageBitmap?, face: EntryFace?, size: Dp, modifier: Modifier = Modifier) {
    when (face) {
        is EntryFace.Card -> MiniCard(face, avatar, size, modifier)
        is EntryFace.WiFi -> MiniWiFi(size, modifier)
        is EntryFace.Bank -> MiniBank(face, size, modifier)
        is EntryFace.Document -> MiniDocument(size, modifier)
        is EntryFace.Ssh -> MiniTerminal(size, modifier)
        is EntryFace.Crypto -> MiniWallet(face, size, modifier)
        null -> EntryIcon(avatar, image, size, modifier)
    }
}

@Composable
private fun MiniCard(face: EntryFace.Card, avatar: EntryAvatar, size: Dp, modifier: Modifier) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        val shape = RoundedCornerShape(size * 0.12f)
        Box(
            Modifier
                .size(size, size * 0.66f)
                .shadow(2.dp, shape)
                .clip(shape)
                .background(Brush.linearGradient(cardArt(face.network, avatar))),
        ) {
            CardChip(size * 0.22f, Modifier.align(Alignment.CenterStart).padding(start = size * 0.1f))
            BrandMark(face.network, null, size * 0.18f, Modifier.align(Alignment.BottomEnd).padding(end = size * 0.08f, bottom = size * 0.06f))
        }
    }
}

@Composable
private fun MiniWiFi(size: Dp, modifier: Modifier) {
    val colors = MaterialTheme.extraColors
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.36f))
            .background(colors.sticker)
            .border(1.dp, colors.stickerEdge, RoundedCornerShape(size * 0.36f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Wifi, contentDescription = null, Modifier.size(size * 0.5f), tint = MaterialTheme.colorScheme.primary)
    }
}

/** A bank account: the bank on the label, the country's flag at its corner. */
@Composable
private fun MiniBank(face: EntryFace.Bank, size: Dp, modifier: Modifier) {
    val colors = MaterialTheme.extraColors
    Box(modifier.size(size)) {
        Box(
            Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(size * 0.36f))
                .background(colors.sticker)
                .border(1.dp, colors.stickerEdge, RoundedCornerShape(size * 0.36f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.AccountBalance, contentDescription = null, Modifier.size(size * 0.5f), tint = MaterialTheme.colorScheme.primary)
        }
        (face.country?.let(Countries::flag) ?: Countries.UNKNOWN_FLAG).let { flag ->
            Text(flag, Modifier.align(Alignment.BottomEnd).offset(x = size * 0.08f, y = size * 0.06f), fontSize = (size.value * 0.32f).sp)
        }
    }
}

/** An identity document: a small paper card with a photo and lines. */
@Composable
private fun MiniDocument(size: Dp, modifier: Modifier) {
    val colors = MaterialTheme.extraColors
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        val shape = RoundedCornerShape(size * 0.12f)
        Box(
            Modifier
                .size(size, size * 0.66f)
                .shadow(2.dp, shape)
                .clip(shape)
                .background(colors.paper)
                .border(1.dp, colors.paperEdge, shape),
        ) {
            Box(
                Modifier
                    .padding(start = size * 0.09f, top = size * 0.13f)
                    .size(size * 0.25f, size * 0.34f)
                    .clip(RoundedCornerShape(size * 0.04f))
                    .background(colors.paperPhoto),
            )
            for ((i, width) in listOf(0.43f, 0.32f, 0.38f).withIndex()) {
                Box(
                    Modifier
                        .padding(start = size * 0.41f, top = size * (0.16f + i * 0.13f))
                        .size(size * width, size * 0.05f)
                        .clip(RoundedCornerShape(size * 0.025f))
                        .background(colors.paperLabel.copy(alpha = 0.7f)),
                )
            }
        }
    }
}

/** The network's colour and sign, on its round badge. */
private fun networkBadge(network: CryptoNetwork?): Pair<Color, String> = when (network) {
    CryptoNetwork.BITCOIN -> Color(0xFFE8871A) to "₿"
    CryptoNetwork.BITCOIN_TESTNET -> Color(0xFF5E8C74) to "₿"
    CryptoNetwork.LITECOIN -> Color(0xFF345D9D) to "Ł"
    CryptoNetwork.DOGECOIN -> Color(0xFFB59A2E) to "Ð"
    CryptoNetwork.ETHEREUM -> Color(0xFF5B6BB0) to "Ξ"
    CryptoNetwork.TRON -> Color(0xFFC8312B) to "T"
    CryptoNetwork.SOLANA -> Color(0xFF7B5BD6) to "◎"
    null -> Color(0xFF59606B) to "◇"
}

@Composable
internal fun NetworkBadge(network: CryptoNetwork?, size: Dp, modifier: Modifier = Modifier) {
    val (colour, sign) = networkBadge(network)
    Box(modifier.size(size).clip(CircleShape).background(colour), contentAlignment = Alignment.Center) {
        Text(sign, color = Color.White, fontSize = (size.value * 0.52f).sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
    }
}

/** A crypto wallet: a small steel plate with words stamped on it, the network's badge at its corner. */
@Composable
private fun MiniWallet(face: EntryFace.Crypto, size: Dp, modifier: Modifier) {
    Box(modifier.size(size)) {
        val shape = RoundedCornerShape(size * 0.36f)
        Box(
            Modifier
                .matchParentSize()
                .clip(shape)
                .background(Brush.verticalGradient(listOf(Color(0xFFE6E9ED), Color(0xFFC3C8D0))))
                .border(1.dp, Color(0x1F000000), shape),
            contentAlignment = Alignment.Center,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(size * 0.09f)) {
                repeat(3) {
                    Row(horizontalArrangement = Arrangement.spacedBy(size * 0.1f)) {
                        repeat(2) { Box(Modifier.size(size * 0.25f, size * 0.07f).clip(RoundedCornerShape(size * 0.035f)).background(Color(0xFF59606B))) }
                    }
                }
            }
        }
        if (face.network != null) {
            NetworkBadge(face.network, size * 0.4f, Modifier.align(Alignment.BottomEnd).offset(x = size * 0.08f, y = size * 0.06f))
        }
    }
}

/** An SSH login: a terminal's prompt. */
@Composable
private fun MiniTerminal(size: Dp, modifier: Modifier) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.36f)).background(Color(0xFF15171C)),
        contentAlignment = Alignment.Center,
    ) {
        Text(">_", color = Color(0xFF7EE2A8), fontFamily = MonospaceFamily, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.36f).sp)
    }
}
