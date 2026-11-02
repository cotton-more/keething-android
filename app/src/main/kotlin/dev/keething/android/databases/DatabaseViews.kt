package dev.keething.android.databases

import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.PlaylistRemove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.keething.android.theme.extraColors
import dev.keething.android.ui.SegmentGap
import dev.keething.android.ui.segmentShape
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.DatabaseList
import dev.keething.vault.databases.Databases
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** The list's databases as the screens show them, and the current one's lock screen; null until first looked at. */
internal data class DatabaseScreens(val lines: List<DatabaseLine>, val lockScreen: LockScreenDatabase?)

/**
 * Looks at the list's files (whether each is there, when it changed) whenever the list changes and on [refresh]: a
 * screen asks again each time it comes back (a card put in, a key file moved back).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class DatabaseLinesModel(list: DatabaseList, private val files: DatabaseFiles) : ViewModel() {
    private val refreshes = MutableStateFlow(0)
    private val root = Environment.getExternalStorageDirectory().path

    val screens: StateFlow<DatabaseScreens?> = combine(list.state, refreshes) { databases, _ -> databases }
        .flatMapLatest { databases ->
            flow {
                val screens = look(databases)
                emit(screens)
                // Not found may be the moment Syncthing replaces the file (SPEC §3.2): once more a little later.
                if (screens.lockScreen?.line?.reach == Reach.NOT_FOUND) {
                    delay(SYNC_REPLACE)
                    emit(look(databases))
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private suspend fun look(databases: Databases): DatabaseScreens = withContext(Dispatchers.IO) {
        val lines = databases.lines(files.lookedAt(databases), root, Instant.now(), ZoneId.systemDefault())
        DatabaseScreens(lines, databases.current?.let { current -> lines.firstOrNull { it.id == current.id }?.let(current::lockScreen) })
    }

    fun refresh() {
        refreshes.value++
    }

    private companion object {
        val SYNC_REPLACE = 3.seconds
    }
}

/** A database's colours in the theme shown (SPEC §6.2; the Mac's `Theme.database`): its dot, its text, its tile's fill. */
internal class DatabaseColors(val dot: Color, val text: Color, val soft: Color)

@Composable
internal fun databaseColors(index: Int): DatabaseColors {
    val shades = DatabasePalette.shades
    val shade = shades[Math.floorMod(index, shades.size)]
    val dark = isSystemInDarkTheme()
    val dot = opaque(if (dark) shade.dotDark else shade.dotLight)
    return DatabaseColors(dot, opaque(if (dark) shade.textDark else shade.textLight), dot.copy(alpha = 0.16f))
}

private fun opaque(rgb: Int): Color = Color(0xFF000000.toInt() or rgb)

/** A database's tile: its initial in its colour on a soft fill of it, in a rounded square; neutral without a colour. */
@Composable
internal fun DatabaseTile(initial: String, colorIndex: Int?, size: Dp = 40.dp) {
    val colors = colorIndex?.let { databaseColors(it) }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(colors?.soft ?: MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initial,
            style = if (size > 48.dp) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors?.text ?: MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/** A database's dot (the lock screen's sheet). */
@Composable
internal fun DatabaseDot(colorIndex: Int) {
    Box(Modifier.size(12.dp).clip(CircleShape).background(databaseColors(colorIndex).dot))
}

/**
 * A database's label on one line (keething-spec databases.name-colour AC3): its name, and what tells it apart from
 * another named alike in the secondary colour ("Personal · Personal-2024.kdbx"); a long one is cut in its middle,
 * never at its end (the file name's end tells copies apart).
 */
@Composable
internal fun DatabaseLabelText(label: DatabaseLabel, style: TextStyle, modifier: Modifier = Modifier, fontWeight: FontWeight? = null) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val text = buildAnnotatedString {
        append(label.name)
        label.addition?.let { withStyle(SpanStyle(color = secondary, fontWeight = FontWeight.Normal)) { append(" · $it") } }
    }
    Text(text, modifier, style = style, fontWeight = fontWeight, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
}

/** Remove “…” from the list?: asked first (SPEC §6.2). */
@Composable
internal fun RemoveFromListDialog(name: String, onRemove: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove “$name” from the list?") },
        text = { Text("Keething forgets it on this phone, with its stored password. The file stays where it is.") },
        confirmButton = { TextButton(onClick = onRemove) { Text("Remove") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The lock screen's sheet (SPEC §6.2): the list's databases, a check on the one shown, each with its folder or that
 * it is not found; many scroll inside it. Then Open other…, New database… and Remove “…” from list….
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DatabasesSheet(
    lines: List<DatabaseLine>,
    onChoose: (DatabaseId) -> Unit,
    onOpenOther: () -> Unit,
    onNew: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val current = lines.firstOrNull { it.isCurrent }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Databases", Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.titleLarge)
            val many = lines.size > MANY
            Column(
                Modifier.then(if (many) Modifier.heightIn(max = 362.dp) else Modifier).clip(RoundedCornerShape(20.dp)).verticalScroll(rememberScrollState()),
            ) {
                lines.forEachIndexed { index, line ->
                    Row(
                        Modifier
                            .padding(bottom = SegmentGap)
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .clip(segmentShape(index, lines.size))
                            .background(MaterialTheme.extraColors.row)
                            .clickable { onChoose(line.id) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        DatabaseDot(line.colorIndex)
                        Column(Modifier.weight(1f)) {
                            DatabaseLabelText(line.label, MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text(
                                line.sheetDetail,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (line.isMissing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (line.isCurrent) Icon(Icons.Rounded.Check, contentDescription = "Shown now", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if (many) {
                Text(
                    "${lines.size} databases · scroll for the rest",
                    Modifier.padding(horizontal = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
            SheetAction(Icons.Rounded.FolderOpen, "Open other…", onOpenOther)
            SheetAction(Icons.Rounded.Add, "New database…", onNew)
            if (current != null) SheetAction(Icons.Rounded.PlaylistRemove, "Remove “${current.name}” from list…", onRemove)
        }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** More than this many databases scroll inside the sheet (the Mac's "many never spread the screen"). */
private const val MANY = 6
