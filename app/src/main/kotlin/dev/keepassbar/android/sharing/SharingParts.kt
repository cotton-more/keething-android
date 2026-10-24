package dev.keepassbar.android.sharing

import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.keepassbar.android.theme.extraColors
import dev.keepassbar.android.ui.EntryIcon
import dev.keepassbar.card.EntryAvatar
import dev.keepassbar.sharing.pictures.Pictures

/** The monogram of the person a request names, coloured as an entry of that title would be. */
@Composable
internal fun PersonIcon(name: String, size: Dp, modifier: Modifier = Modifier) {
    EntryIcon(EntryAvatar.of(name), null, size, modifier)
}

/** The six pictures of a request (SPEC §10), three a row, each with the name to say it by. */
@Composable
internal fun PictureGrid(indices: List<Int>, modifier: Modifier = Modifier) {
    val pictures = Pictures.named(indices)
    Column(
        modifier.clearAndSetSemantics { contentDescription = "The pictures: " + pictures.joinToString(", ") { it.name } },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        pictures.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { picture ->
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .padding(top = 10.dp, bottom = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(picture.emoji, fontSize = 32.sp, lineHeight = 40.sp)
                        Text(
                            picture.name,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** A quiet box that explains: what a screen's choice means, what is sent, what is not. */
@Composable
internal fun InfoBox(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A request's pictures were checked, or are still to be checked (SPEC §10). */
@Composable
internal fun CheckedChip(checked: Boolean) {
    val colors = MaterialTheme.extraColors
    val (container, content) = if (checked) {
        MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        colors.warnContainer to colors.onWarnContainer
    }
    Row(
        Modifier.clip(RoundedCornerShape(14.dp)).background(container).height(28.dp).padding(start = 8.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(if (checked) Icons.Rounded.Check else Icons.Rounded.ErrorOutline, contentDescription = null, Modifier.size(15.dp), tint = content)
        Text(if (checked) "Checked" else "Check first", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = content)
    }
}

/** The buttons at the foot of a screen, above the navigation bar, on the screen's colour. */
@Composable
internal fun BottomActions(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

/** A screen's headline and the line under it, for the top of a sharing screen. */
@Composable
internal fun TitleBlock(title: String, subtitle: String?, modifier: Modifier = Modifier, overline: String? = null) {
    Column(modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        overline?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Centred words where a list would be: nothing there, or why not. */
@Composable
internal fun Explanation(title: String, text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/**
 * A button's icon and label on one line. On a narrow screen or with a large font the label first gets smaller, down to
 * 12 sp; if it still does not fit beside the icon, the icon goes and the label may shrink to 11 sp; then the [short]
 * label is shown, and only then does it end in "…". Never a second line ("Ask for an / entry").
 */
@Composable
internal fun ButtonLabel(text: String, icon: ImageVector? = null, short: String = text, iconSize: Dp = 24.dp, gap: Dp = 10.dp) {
    val style = LocalTextStyle.current
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints {
        val width = constraints.maxWidth
        val besideIcon = if (icon == null) width else width - with(density) { (iconSize + gap).roundToPx() }
        val (label, size, withIcon) = remember(text, short, style, width, besideIcon) {
            fun fits(label: String, size: Float, room: Int) =
                measurer.measure(label, style.copy(fontSize = size.sp), maxLines = 1, softWrap = false).size.width <= room
            fun largest(label: String, room: Int, smallest: Float): Float {
                var size = style.fontSize.value
                while (size > smallest && !fits(label, size, room)) size -= 0.5f
                return size
            }
            val beside = largest(text, besideIcon, 12f)
            val alone = largest(text, width, 11f)
            when {
                icon != null && fits(text, beside, besideIcon) -> Triple(text, beside, true)
                fits(text, alone, width) || short == text -> Triple(text, alone, false)
                else -> Triple(short, largest(short, width, 11f), false)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (withIcon && icon != null) {
                Icon(icon, contentDescription = null, Modifier.size(iconSize))
                Spacer(Modifier.width(gap))
            }
            Text(label, fontSize = size.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
    }
}
