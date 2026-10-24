package dev.keething.android.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.keething.card.EntryAvatar
import dev.keething.domain.vault.CustomIcon

/**
 * An entry's picture: the database's custom icon (a favicon KeePassXC downloaded) when it has one, else the
 * monogram in the entry's colour, the same colour as on the Mac (SPEC §6.1). Nothing is fetched.
 */
@Composable
internal fun EntryIcon(avatar: EntryAvatar, image: ImageBitmap?, size: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(size * 0.36f)
    if (image != null) {
        Box(
            modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Image(image, contentDescription = null, Modifier.padding(size * 0.18f).size(size * 0.64f))
        }
        return
    }
    val (top, bottom) = AVATAR_PALETTE[avatar.colorIndex.coerceIn(AVATAR_PALETTE.indices)]
    Box(
        modifier.size(size).clip(shape).background(Brush.linearGradient(listOf(top, bottom))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            avatar.initial,
            color = Color.White,
            fontSize = (size.value * 0.45f).sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** The custom icons of one vault, decoded when first shown. Not secrets: kept until the vault changes. */
internal class CustomIconImages(private val icons: Map<String, CustomIcon>) {
    private val decoded = HashMap<String, ImageBitmap?>()

    operator fun get(id: String?): ImageBitmap? {
        if (id == null) return null
        return decoded.getOrPut(id) {
            icons[id]?.image?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.asImageBitmap()
        }
    }
}

/** The Mac app's avatar gradients, in the same order (Mac `Theme.avatarPalette`). */
internal val AVATAR_PALETTE = listOf(
    Color(0xFF5B8DEF) to Color(0xFF3A63D8),
    Color(0xFF3FB6C4) to Color(0xFF2A8FA6),
    Color(0xFF4CBF7A) to Color(0xFF2F9A5C),
    Color(0xFFE8A33A) to Color(0xFFD18616),
    Color(0xFFF28C5B) to Color(0xFFE0683A),
    Color(0xFFE86F8F) to Color(0xFFD04A74),
    Color(0xFF9B7BE8) to Color(0xFF7A55D0),
    Color(0xFF7C8AA5) to Color(0xFF5B6A86),
)
